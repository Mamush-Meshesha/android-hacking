"""
BLE Scanner Module

Responsible for discovering BLE peripherals advertising our custom service.

Under the hood:
- Uses bleak's BleakScanner which wraps platform-specific BLE APIs
- On Linux: Uses BlueZ D-Bus API
- Scans for advertising packets containing our service UUID
- Filters devices to find our Android app
"""

import asyncio
import logging
from typing import Optional, List
from bleak import BleakScanner
from bleak.backends.device import BLEDevice

# Our custom service UUID (must match Android app)
SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"

logger = logging.getLogger(__name__)


class BLEDeviceScanner:
    """
    Scans for BLE devices advertising our remote control service.
    
    Technical details:
    - Passive scanning: listens for advertising packets
    - Advertising packets contain service UUIDs
    - We filter by our custom service UUID
    """
    
    def __init__(self, service_uuid: str = SERVICE_UUID):
        self.service_uuid = service_uuid
        self.discovered_devices: List[BLEDevice] = []
    
    async def scan(self, duration: float = 5.0) -> List[BLEDevice]:
        """
        Scan for BLE devices advertising our service.
        
        Args:
            duration: Scan duration in seconds
            
        Returns:
            List of discovered BLE devices
            
        Under the hood:
        1. BleakScanner starts platform BLE adapter
        2. Adapter enters scanning mode (passive)
        3. Receives advertising packets from nearby devices
        4. Filters by service UUID in advertisement data
        5. Returns list of matching devices
        """
        logger.info(f"Starting BLE scan for {duration} seconds...")
        logger.info(f"Looking for service UUID: {self.service_uuid}")
        
        # Scan for devices
        # Note: BleakScanner.discover() returns ALL devices
        # We filter by service UUID manually
        devices = await BleakScanner.discover(timeout=duration)
        
        # Filter devices advertising our service
        matching_devices = []
        for device in devices:
            # Check if device advertises our service UUID
            if device.metadata.get("uuids"):
                advertised_uuids = device.metadata["uuids"]
                if self.service_uuid.lower() in [uuid.lower() for uuid in advertised_uuids]:
                    matching_devices.append(device)
                    logger.info(f"Found matching device: {device.name} ({device.address})")
        
        if not matching_devices:
            logger.warning("No devices found advertising our service UUID")
            logger.info("Make sure the Android app is running and Bluetooth is enabled")
        
        self.discovered_devices = matching_devices
        return matching_devices
    
    async def scan_for_device(self, device_name: str, duration: float = 10.0) -> Optional[BLEDevice]:
        """
        Scan for a specific device by name.
        
        Args:
            device_name: Name of the device to find
            duration: Maximum scan duration
            
        Returns:
            BLEDevice if found, None otherwise
        """
        logger.info(f"Scanning for device: {device_name}")
        
        devices = await self.scan(duration)
        
        for device in devices:
            if device.name and device_name.lower() in device.name.lower():
                logger.info(f"Found target device: {device.name} ({device.address})")
                return device
        
        logger.warning(f"Device '{device_name}' not found")
        return None
    
    def print_discovered_devices(self):
        """Print all discovered devices in a user-friendly format."""
        if not self.discovered_devices:
            print("\nNo devices discovered.")
            return
        
        print(f"\n{'='*60}")
        print(f"Discovered {len(self.discovered_devices)} device(s):")
        print(f"{'='*60}")
        
        for idx, device in enumerate(self.discovered_devices, 1):
            print(f"\n{idx}. Name: {device.name or 'Unknown'}")
            print(f"   Address: {device.address}")
            print(f"   RSSI: {device.rssi} dBm")
            if device.metadata.get("uuids"):
                print(f"   Services: {', '.join(device.metadata['uuids'][:3])}...")


async def main():
    """
    Example usage of BLE scanner.
    
    This demonstrates:
    1. Creating a scanner instance
    2. Scanning for devices
    3. Displaying results
    """
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    scanner = BLEDeviceScanner()
    
    print("Starting BLE scan...")
    print("Make sure your Android app is running and advertising!")
    
    devices = await scanner.scan(duration=10.0)
    scanner.print_discovered_devices()
    
    if devices:
        print(f"\n✓ Found {len(devices)} device(s) advertising our service")
    else:
        print("\n✗ No devices found")
        print("\nTroubleshooting:")
        print("1. Is the Android app running?")
        print("2. Is Bluetooth enabled on both devices?")
        print("3. Are devices within range (~10m)?")
        print("4. Check Android app logs for GATT server status")


if __name__ == "__main__":
    asyncio.run(main())
