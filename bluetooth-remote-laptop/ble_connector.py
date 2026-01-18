"""
BLE Connector Module

Manages connection to the Android BLE GATT server.

Under the hood:
- Establishes BLE connection using BleakClient
- Discovers GATT services and characteristics
- Maintains connection state
- Handles reconnection logic
- Manages MTU (Maximum Transmission Unit) negotiation

GATT Primer:
- GATT = Generic Attribute Profile
- Hierarchical structure: Service -> Characteristic -> Descriptor
- Service: Collection of related characteristics (identified by UUID)
- Characteristic: Data value with properties (read/write/notify)
- Our app uses one service with two characteristics (command, response)
"""

import asyncio
import logging
from typing import Optional, Callable
from bleak import BleakClient
from bleak.backends.device import BLEDevice
from bleak.exc import BleakError

# UUIDs must match Android app
SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
COMMAND_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"
RESPONSE_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef2"

logger = logging.getLogger(__name__)


class BLEConnector:
    """
    Manages BLE connection to Android GATT server.
    
    Connection lifecycle:
    1. Connect to device (BLE link layer)
    2. Discover services (GATT service discovery)
    3. Verify our service exists
    4. Verify characteristics exist
    5. Subscribe to notifications (optional)
    6. Ready for communication
    """
    
    def __init__(self, device: BLEDevice):
        self.device = device
        self.client: Optional[BleakClient] = None
        self.is_connected = False
        self.response_callback: Optional[Callable] = None
        
    async def connect(self, timeout: float = 10.0) -> bool:
        """
        Establish connection to BLE device.
        
        Args:
            timeout: Connection timeout in seconds
            
        Returns:
            True if connected successfully
            
        Under the hood:
        1. BleakClient initiates BLE connection request
        2. BLE stack performs link layer connection
        3. If devices are paired, encryption is established
        4. GATT service discovery happens automatically
        5. MTU negotiation occurs (default 23 bytes, can go up to 517)
        """
        try:
            logger.info(f"Connecting to {self.device.name} ({self.device.address})...")
            
            # Create BleakClient instance
            # This doesn't connect yet, just prepares the client
            self.client = BleakClient(self.device, timeout=timeout)
            
            # Establish connection
            # This is where the actual BLE connection happens
            await self.client.connect()
            
            self.is_connected = True
            logger.info(f"✓ Connected to {self.device.name}")
            
            # Negotiate MTU
            try:
                await self.client.request_mtu(512)
                logger.info(f"MTU negotiated to: {self.client.mtu_size} bytes")
            except Exception as e:
                logger.warning(f"MTU negotiation failed: {e}")
                logger.warning(f"Using default MTU: {self.client.mtu_size} bytes")
            
            logger.info("(MTU = Maximum Transmission Unit - max data per packet)")
            
            # Verify our service exists
            services = await self.client.get_services()
            service = services.get_service(SERVICE_UUID)
            
            if not service:
                logger.error(f"Service {SERVICE_UUID} not found!")
                logger.error("Make sure Android app is running with GATT server active")
                await self.disconnect()
                return False
            
            logger.info(f"✓ Found service: {SERVICE_UUID}")
            
            # Verify characteristics exist
            command_char = service.get_characteristic(COMMAND_CHAR_UUID)
            response_char = service.get_characteristic(RESPONSE_CHAR_UUID)
            
            if not command_char:
                logger.error(f"Command characteristic {COMMAND_CHAR_UUID} not found!")
                await self.disconnect()
                return False
                
            if not response_char:
                logger.error(f"Response characteristic {RESPONSE_CHAR_UUID} not found!")
                await self.disconnect()
                return False
            
            logger.info(f"✓ Found command characteristic: {COMMAND_CHAR_UUID}")
            logger.info(f"✓ Found response characteristic: {RESPONSE_CHAR_UUID}")
            
            # Log characteristic properties
            logger.info(f"Command char properties: {command_char.properties}")
            logger.info(f"Response char properties: {response_char.properties}")
            
            return True
            
        except BleakError as e:
            logger.error(f"Connection failed: {e}")
            self.is_connected = False
            return False
        except Exception as e:
            logger.error(f"Unexpected error during connection: {e}")
            self.is_connected = False
            return False
    
    async def disconnect(self):
        """
        Disconnect from BLE device.
        
        Under the hood:
        - Sends BLE disconnect request
        - Cleans up connection resources
        - Stops any active notifications
        """
        if self.client and self.is_connected:
            try:
                logger.info("Disconnecting...")
                await self.client.disconnect()
                self.is_connected = False
                logger.info("✓ Disconnected")
            except Exception as e:
                logger.error(f"Error during disconnect: {e}")
    
    async def write_command(self, data: bytes) -> bool:
        """
        Write data to command characteristic.
        
        Args:
            data: Bytes to write (typically JSON command)
            
        Returns:
            True if write successful
            
        Under the hood:
        1. Data is split into MTU-sized chunks if needed
        2. Each chunk is sent as a BLE write request
        3. Android GATT server receives onCharacteristicWrite callback
        4. Server processes the data and executes command
        
        Note: BLE has MTU limits (default 23 bytes, negotiated up to 517)
        If data > MTU, bleak handles chunking automatically
        """
        if not self.is_connected or not self.client:
            logger.error("Not connected to device")
            return False
        
        try:
            logger.info(f"Writing {len(data)} bytes to command characteristic...")
            
            # Check if data fits in MTU
            if len(data) > self.client.mtu_size - 3:  # -3 for ATT overhead
                logger.warning(f"Data size ({len(data)} bytes) exceeds MTU ({self.client.mtu_size} bytes)")
                logger.warning("Data will be sent in multiple packets (handled by OS/Bleak)")
            
            # Write to characteristic with retry
            for attempt in range(3):
                try:
                    await self.client.write_gatt_char(COMMAND_CHAR_UUID, data, response=True)
                    logger.info("✓ Command written successfully")
                    return True
                except Exception as e:
                    logger.warning(f"Write attempt {attempt + 1} failed: {e}")
                    if attempt == 2:
                        raise e
                    await asyncio.sleep(0.5)
            
            return False
            
        except BleakError as e:
            logger.error(f"Write failed: {e}")
            if "UnknownObject" in str(e):
                logger.error("Device might have disconnected or services changed. Please reconnect.")
                self.is_connected = False
            return False
        except Exception as e:
            logger.error(f"Unexpected error during write: {e}")
            return False
    
    async def read_response(self) -> Optional[bytes]:
        """
        Read data from response characteristic.
        
        Returns:
            Bytes read from characteristic, or None if failed
            
        Under the hood:
        - Sends BLE ATT Read Request
        - Android GATT server receives onCharacteristicRead callback
        - Server returns current value of characteristic
        """
        if not self.is_connected or not self.client:
            logger.error("Not connected to device")
            return None
        
        try:
            logger.info("Reading response characteristic...")
            data = await self.client.read_gatt_char(RESPONSE_CHAR_UUID)
            logger.info(f"✓ Read {len(data)} bytes")
            return data
            
        except BleakError as e:
            logger.error(f"Read failed: {e}")
            return None
        except Exception as e:
            logger.error(f"Unexpected error during read: {e}")
            return None
    
    async def start_notify(self, callback: Callable[[int, bytearray], None]):
        """
        Subscribe to notifications from response characteristic.
        
        Args:
            callback: Function called when notification received
            
        Under the hood:
        1. Sends BLE ATT Write Request to CCCD (Client Characteristic Configuration Descriptor)
        2. CCCD value set to 0x0001 (enable notifications)
        3. Android GATT server can now send notifications
        4. When server calls notifyCharacteristicChanged(), callback is invoked
        
        Notifications vs Indications:
        - Notification: No acknowledgment (faster, less reliable)
        - Indication: Requires acknowledgment (slower, more reliable)
        We use notifications for simplicity
        """
        if not self.is_connected or not self.client:
            logger.error("Not connected to device")
            return
        
        try:
            logger.info("Subscribing to response notifications...")
            await self.client.start_notify(RESPONSE_CHAR_UUID, callback)
            self.response_callback = callback
            logger.info("✓ Subscribed to notifications")
            
        except BleakError as e:
            logger.error(f"Failed to subscribe to notifications: {e}")
        except Exception as e:
            logger.error(f"Unexpected error during notification setup: {e}")
    
    async def stop_notify(self):
        """Stop receiving notifications."""
        if not self.is_connected or not self.client:
            return
        
        try:
            logger.info("Unsubscribing from notifications...")
            await self.client.stop_notify(RESPONSE_CHAR_UUID)
            logger.info("✓ Unsubscribed from notifications")
            
        except Exception as e:
            logger.error(f"Error stopping notifications: {e}")


async def main():
    """
    Example usage of BLE connector.
    
    This demonstrates:
    1. Connecting to a device
    2. Verifying GATT services
    3. Reading/writing characteristics
    """
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # For testing, you need to provide a BLEDevice
    # In practice, you'd get this from BLEDeviceScanner
    print("This module requires a BLEDevice from the scanner.")
    print("Use main.py for complete workflow.")


if __name__ == "__main__":
    asyncio.run(main())
