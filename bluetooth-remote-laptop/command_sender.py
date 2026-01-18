"""
Command Sender Module

High-level interface for sending commands to Android device.

This module:
- Constructs JSON command payloads
- Handles command serialization
- Provides user-friendly command methods
- Validates command structure
"""

import json
import time
import logging
from typing import Dict, Any, Optional
from ble_connector import BLEConnector

logger = logging.getLogger(__name__)


class CommandSender:
    """
    Sends structured commands to Android device via BLE.
    
    Command flow:
    1. Python: Construct JSON command
    2. Python: Serialize to bytes (UTF-8)
    3. BLE: Write to command characteristic
    4. Android: Receive bytes in onCharacteristicWrite
    5. Android: Deserialize JSON
    6. Android: Parse and execute command
    """
    
    def __init__(self, connector: BLEConnector):
        self.connector = connector
    
    def _create_command(self, cmd_type: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        """
        Create a command dictionary with standard structure.
        
        Args:
            cmd_type: Command type (e.g., 'show_toast')
            payload: Command-specific data
            
        Returns:
            Complete command dictionary
        """
        return {
            "type": cmd_type,
            "payload": payload,
            "timestamp": int(time.time())
        }
    
    def _serialize_command(self, command: Dict[str, Any]) -> bytes:
        """
        Serialize command to bytes for BLE transmission.
        
        Args:
            command: Command dictionary
            
        Returns:
            UTF-8 encoded JSON bytes
            
        Under the hood:
        - json.dumps() converts dict to JSON string
        - .encode('utf-8') converts string to bytes
        - Bytes are what BLE characteristics accept
        """
        json_str = json.dumps(command)
        logger.debug(f"Command JSON: {json_str}")
        return json_str.encode('utf-8')
    
    async def send_command(self, cmd_type: str, payload: Dict[str, Any]) -> bool:
        """
        Send a command to the Android device.
        
        Args:
            cmd_type: Command type
            payload: Command payload
            
        Returns:
            True if command sent successfully
        """
        command = self._create_command(cmd_type, payload)
        data = self._serialize_command(command)
        
        logger.info(f"Sending command: {cmd_type}")
        return await self.connector.write_command(data)
    
    async def show_toast(self, message: str, duration: str = "short") -> bool:
        """
        Show a toast message on Android device.
        
        Args:
            message: Toast message text
            duration: "short" (2s) or "long" (3.5s)
            
        Returns:
            True if command sent successfully
            
        What happens on Android:
        1. Receives command
        2. Parses JSON
        3. Calls Toast.makeText(context, message, duration).show()
        4. User sees toast notification
        """
        payload = {
            "message": message,
            "duration": duration
        }
        return await self.send_command("show_toast", payload)
    
    async def open_app(self, package_name: str) -> bool:
        """
        Open an app on Android device.
        
        Args:
            package_name: Android package name (e.g., 'com.android.chrome')
            
        Returns:
            True if command sent successfully
            
        What happens on Android:
        1. Receives command
        2. Parses package name
        3. Calls packageManager.getLaunchIntentForPackage(package)
        4. Calls startActivity(intent)
        5. App launches (if installed)
        
        Common package names:
        - Chrome: com.android.chrome
        - Settings: com.android.settings
        - Camera: com.android.camera2
        - Messages: com.google.android.apps.messaging
        """
        payload = {
            "package": package_name
        }
        return await self.send_command("open_app", payload)
    
    async def log_action(self, message: str) -> bool:
        """
        Log a message on Android device.
        
        Args:
            message: Message to log
            
        Returns:
            True if command sent successfully
            
        What happens on Android:
        1. Receives command
        2. Calls Log.i(TAG, message)
        3. Message appears in logcat
        4. Can be viewed with: adb logcat | grep BTRemote
        """
        payload = {
            "message": message
        }
        return await self.send_command("log_action", payload)
    
    async def get_status(self) -> bool:
        """
        Request status from Android device.
        
        Returns:
            True if command sent successfully
            
        What happens on Android:
        1. Receives command
        2. Collects device info (battery, time, etc.)
        3. Sends response via notification characteristic
        4. Laptop receives notification with status data
        """
        payload = {}
        return await self.send_command("get_status", payload)
    
    async def list_files(self, path: str = "/sdcard") -> bool:
        """
        List files in a directory on the Android device.
        
        Args:
            path: Directory path to list (default: /sdcard)
        """
        payload = {"path": path}
        return await self.send_command("list_files", payload)

    async def send_sms(self, number: str, message: str) -> bool:
        """
        Send an SMS from the Android device.
        
        Args:
            number: Phone number to send to
            message: Message content
        """
        payload = {
            "number": number,
            "message": message
        }
        return await self.send_command("send_sms", payload)

    async def audio_control(self, action: str, value: int = -1) -> bool:
        """
        Control audio on the Android device.
        
        Args:
            action: 'set_volume', 'volume_up', 'volume_down'
            value: Volume level (0-100) for set_volume
        """
        payload = {
            "action": action,
            "value": value
        }
        return await self.send_command("audio_control", payload)

    async def list_files(self, path: str = "/sdcard") -> bool:
        """
        List files in a directory on the Android device.
        
        Args:
            path: Directory path to list (default: /sdcard)
        """
        payload = {"path": path}
        return await self.send_command("list_files", payload)

    async def send_sms(self, number: str, message: str) -> bool:
        """
        Send an SMS from the Android device.
        
        Args:
            number: Phone number to send to
            message: Message content
        """
        payload = {
            "number": number,
            "message": message
        }
        return await self.send_command("send_sms", payload)

    async def audio_control(self, action: str, value: int = -1) -> bool:
        """
        Control audio on the Android device.
        
        Args:
            action: 'set_volume', 'volume_up', 'volume_down'
            value: Volume level (0-100) for set_volume
        """
        payload = {
            "action": action,
            "value": value
        }
        return await self.send_command("audio_control", payload)

    async def list_sms(self, limit: int = 50) -> bool:
        """
        List SMS messages from the Android device.
        
        Args:
            limit: Max number of messages to retrieve (default: 50)
        """
        payload = {"limit": limit}
        return await self.send_command("list_sms", payload)

    async def open_file(self, path: str) -> bool:
        """Open a file on the Android device."""
        payload = {"path": path}
        return await self.send_command("open_file", payload)

    async def copy_file(self, source: str, dest: str) -> bool:
        """Copy a file on the Android device."""
        payload = {"source": source, "dest": dest}
        return await self.send_command("copy_file", payload)

    async def read_file(self, path: str) -> bool:
        """Read a file from the Android device (download)."""
        payload = {"path": path}
        return await self.send_command("read_file", payload)

    async def send_custom_command(self, cmd_type: str, payload: Dict[str, Any]) -> bool:
        """
        Send a custom command.
        
        Args:
            cmd_type: Custom command type
            payload: Custom payload
            
        Returns:
            True if command sent successfully
            
        Use this for extending the protocol with new command types.
        Make sure to implement the handler on Android side!
        """
        return await self.send_command(cmd_type, payload)


async def main():
    """Example usage of command sender."""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    print("This module requires a BLEConnector instance.")
    print("Use main.py for complete workflow.")


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
