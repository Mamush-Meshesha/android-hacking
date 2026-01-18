"""
Bluetooth Remote Control - Main Application

Complete workflow orchestrating scanner, connector, and command sender.

This is the entry point for controlling your Android device via BLE.

Usage:
    python main.py
    
Then follow the interactive prompts.
"""

import asyncio
import logging
import sys
from typing import Optional

from ble_scanner import BLEDeviceScanner
from ble_connector import BLEConnector
from command_sender import CommandSender
from bleak.backends.device import BLEDevice


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class BluetoothRemoteControl:
    """
    Main application class orchestrating the complete BLE workflow.
    
    Workflow:
    1. Scan for devices
    2. Connect to selected device
    3. Send commands interactively
    4. Handle responses
    5. Disconnect gracefully
    """
    
    def __init__(self):
        self.scanner = BLEDeviceScanner()
        self.connector: Optional[BLEConnector] = None
        self.command_sender: Optional[CommandSender] = None
        self.selected_device: Optional[BLEDevice] = None
    
    async def scan_and_select_device(self) -> bool:
        """
        Scan for devices and let user select one.
        
        Returns:
            True if device selected successfully
        """
        print("\n" + "="*60)
        print("SCANNING FOR BLE DEVICES")
        print("="*60)
        print("Make sure your Android app is running and advertising!")
        print("Scanning for 10 seconds...\n")
        
        devices = await self.scanner.scan(duration=10.0)
        
        if not devices:
            print("\n✗ No devices found advertising our service.")
            print("\nTroubleshooting:")
            print("1. Is the Android app running?")
            print("2. Is Bluetooth enabled on both devices?")
            print("3. Did you grant Bluetooth permissions to the Android app?")
            print("4. Are devices within range (~10 meters)?")
            return False
        
        # Display discovered devices
        self.scanner.print_discovered_devices()
        
        # Auto-select if only one device
        if len(devices) == 1:
            self.selected_device = devices[0]
            print(f"\n✓ Auto-selected: {self.selected_device.name}")
            return True
        
        # Let user select
        while True:
            try:
                choice = input(f"\nSelect device (1-{len(devices)}): ")
                idx = int(choice) - 1
                if 0 <= idx < len(devices):
                    self.selected_device = devices[idx]
                    print(f"✓ Selected: {self.selected_device.name}")
                    return True
                else:
                    print(f"Invalid choice. Enter 1-{len(devices)}")
            except ValueError:
                print("Invalid input. Enter a number.")
            except KeyboardInterrupt:
                print("\n\nCancelled.")
                return False
    
    async def connect_to_device(self) -> bool:
        """
        Connect to the selected device.
        
        Returns:
            True if connected successfully
        """
        if not self.selected_device:
            logger.error("No device selected")
            return False
        
        print("\n" + "="*60)
        print("CONNECTING TO DEVICE")
        print("="*60)
        
        self.connector = BLEConnector(self.selected_device)
        
        # Buffer for chunked messages
        self.chunk_buffer = {}  # {seq: data}
        self.expected_chunks = 0
        self.received_chunks = 0
        
        # Define notification callback
        def notification_handler(sender: int, data: bytearray):
            """
            Handle notifications from Android device.
            """
            try:
                message = data.decode('utf-8')
                
                # Check for chunking
                if message.startswith("CHUNK:"):
                    try:
                        # Format: CHUNK:seq/total:data
                        header_end = message.find(":", 6)
                        if header_end != -1:
                            header = message[6:header_end]
                            seq_str, total_str = header.split("/")
                            seq = int(seq_str)
                            total = int(total_str)
                            chunk_data = message[header_end+1:]
                            
                            if seq == 1:
                                self.chunk_buffer = {}
                                self.expected_chunks = total
                                self.received_chunks = 0
                                print(f"Receiving large response ({total} chunks)...", end="\r")
                            
                            self.chunk_buffer[seq] = chunk_data
                            self.received_chunks += 1
                            
                            if self.received_chunks == self.expected_chunks:
                                # Reassemble
                                full_message = ""
                                for i in range(1, self.expected_chunks + 1):
                                    full_message += self.chunk_buffer.get(i, "")
                                
                                # Process full message
                                process_message(full_message)
                                self.chunk_buffer = {}
                                self.expected_chunks = 0
                            return
                    except Exception as e:
                        logger.error(f"Error processing chunk: {e}")
                        return

                # Process non-chunked message
                process_message(message)
                
            except Exception as e:
                logger.error(f"Error decoding notification: {e}")

        def process_message(message: str):
            """Process the complete message string."""
            # Try to parse JSON for pretty printing
            try:
                import json
                from datetime import datetime
                
                resp = json.loads(message)
                status = resp.get("status")
                msg_content = resp.get("message")
                
                if status == "list_files":
                    # Parse the inner JSON string for files
                    file_data = json.loads(msg_content)
                    path = file_data.get("path")
                    files = file_data.get("files", [])
                    
                    print(f"\n📂 Directory Listing: {path}")
                    print(f"{'Name':<30} {'Type':<10} {'Size':<10}")
                    print("-" * 50)
                    for f in files:
                        ftype = "DIR" if f.get("is_dir") else "FILE"
                        size = f"{f.get('size')} B"
                        print(f"{f.get('name'):<30} {ftype:<10} {size:<10}")
                    print("-" * 50)
                    print(f"Total: {len(files)} items\n")
                    return

                elif status == "list_sms":
                    # Parse the inner JSON string for SMS
                    sms_data = json.loads(msg_content)
                    messages = sms_data.get("messages", [])
                    
                    print(f"\n💬 SMS Inbox (Last {len(messages)})")
                    print("-" * 60)
                    for msg in messages:
                        addr = msg.get("address", "Unknown")
                        body = msg.get("body", "")
                        date_ms = msg.get("date", 0)
                        date_str = datetime.fromtimestamp(date_ms / 1000.0).strftime('%Y-%m-%d %H:%M')
                        
                        print(f"From: {addr} ({date_str})")
                        print(f"Msg:  {body}")
                        print("-" * 60)
                    print("\n")
                    return
                    
            except Exception:
                # Not JSON or parsing failed, fall back to raw print
                pass
            
            print(f"\n📱 Notification from Android: {message}")
        
        # Connect
        success = await self.connector.connect()
        
        if not success:
            print("\n✗ Connection failed")
            return False
        
        # Subscribe to notifications
        await self.connector.start_notify(notification_handler)
        
        # Create command sender
        self.command_sender = CommandSender(self.connector)
        
        print("\n✓ Connected and ready!")
        return True
    
    async def interactive_command_loop(self):
        """
        Interactive command loop for sending commands.
        
        User can type commands and see results in real-time.
        """
        print("\n" + "="*60)
        print("INTERACTIVE COMMAND MODE")
        print("="*60)
        print("\nAvailable commands:")
        print("  toast <msg>           - Show toast")
        print("  app <pkg>             - Open app")
        print("  log <msg>             - Log message")
        print("  status                - Get status")
        print("  list [path]           - List files")
        print("  sms <num> <msg>       - Send SMS")
        print("  volume <action> [val] - Control volume")
        print("  quit                  - Exit")
        print("\nExamples:")
        print("  toast Hello from laptop!")
        print("  app com.android.chrome")
        print("  log Test message")
        print("="*60)
        
        while True:
            try:
                # Get user input
                command = input("\n> ").strip()
                
                if not command:
                    continue
                
                # Parse command
                parts = command.split(maxsplit=1)
                cmd_type = parts[0].lower()
                
                if cmd_type == "quit" or cmd_type == "exit":
                    print("\nDisconnecting...")
                    break
                
                elif cmd_type == "help":
                    print("\nAvailable commands:")
                    print("  toast <message>       - Show toast on phone")
                    print("  app <package_name>    - Open app (e.g., com.android.chrome)")
                    print("  log <message>         - Log to logcat")
                    print("  status                - Get device status")
                    print("  list [path]           - List files (default: /sdcard)")
                    print("  sms <num> <msg>       - Send SMS")
                    print("  volume <action> [val] - Control volume (set/up/down)")
                    print("  quit                  - Exit")
                
                elif cmd_type == "toast":
                    if len(parts) < 2:
                        print("Usage: toast <message>")
                        continue
                    message = parts[1]
                    success = await self.command_sender.show_toast(message)
                    if success:
                        print("✓ Toast command sent")
                    else:
                        print("✗ Failed to send command")
                
                elif cmd_type == "app":
                    if len(parts) < 2:
                        print("Usage: app <package_name>")
                        print("Example: app com.android.chrome")
                        continue
                    package = parts[1]
                    success = await self.command_sender.open_app(package)
                    if success:
                        print("✓ App launch command sent")
                    else:
                        print("✗ Failed to send command")
                
                elif cmd_type == "log":
                    if len(parts) < 2:
                        print("Usage: log <message>")
                        continue
                    message = parts[1]
                    success = await self.command_sender.log_action(message)
                    if success:
                        print("✓ Log command sent")
                        print("  Check Android logcat: adb logcat | grep BTRemote")
                    else:
                        print("✗ Failed to send command")
                
                elif cmd_type == "status":
                    success = await self.command_sender.get_status()
                    if success:
                        print("✓ Status request sent")
                        print("  Waiting for notification...")
                    else:
                        print("✗ Failed to send command")

                elif cmd_type == "list":
                    arg = parts[1] if len(parts) > 1 else "/sdcard"
                    
                    if arg.lower().startswith("sms"):
                        # Check for limit: list sms 10 or list sms all
                        sub_parts = arg.split()
                        limit = 50 # Default
                        
                        if len(sub_parts) > 1:
                            if sub_parts[1].lower() == "all":
                                limit = -1
                            else:
                                try:
                                    limit = int(sub_parts[1])
                                except ValueError:
                                    pass
                        
                        success = await self.command_sender.list_sms(limit)
                        if success:
                            print(f"✓ List SMS command sent (limit: {'all' if limit == -1 else limit})")
                            print("  Waiting for response...")
                        else:
                            print("✗ Failed to send command")
                    else:
                        success = await self.command_sender.list_files(arg)
                        if success:
                            print(f"✓ List files command sent for: {arg}")
                            print("  Waiting for response...")
                        else:
                            print("✗ Failed to send command")
                    
                elif cmd_type == "sms":
                    if len(parts) < 2:
                        print("Usage: sms <number> <message>")
                        continue
                    
                    # Parse arguments from the payload part
                    args = parts[1].split(maxsplit=1)
                    if len(args) < 2:
                        print("Usage: sms <number> <message>")
                        continue
                        
                    number = args[0]
                    message = args[1]
                    success = await self.command_sender.send_sms(number, message)
                    if success:
                        print(f"✓ SMS command sent to {number}")
                    else:
                        print("✗ Failed to send command")
                    
                elif cmd_type == "volume":
                    if len(parts) < 2:
                        print("Usage: volume <set/up/down> [value]")
                        continue
                    
                    args = parts[1].split()
                    action = args[0]
                    value = -1
                    
                    if action == "set":
                        if len(args) < 2:
                            print("Usage: volume set <0-100>")
                            continue
                        try:
                            value = int(args[1])
                            success = await self.command_sender.audio_control("set_volume", value)
                        except ValueError:
                            print("Invalid volume value")
                            continue
                    elif action == "up":
                        success = await self.command_sender.audio_control("volume_up")
                    elif action == "down":
                        success = await self.command_sender.audio_control("volume_down")
                    else:
                        print("Unknown volume action. Use: set, up, down")
                        continue

                    if success:
                        print(f"✓ Volume command sent: {action}")
                    else:
                        print("✗ Failed to send command")
                
                else:
                    print(f"Unknown command: {cmd_type}")
                    print("Type 'help' for available commands")
            
            except KeyboardInterrupt:
                print("\n\nInterrupted. Disconnecting...")
                break
            except Exception as e:
                logger.error(f"Error processing command: {e}")
    
    async def disconnect(self):
        """Disconnect from device."""
        if self.connector:
            await self.connector.stop_notify()
            await self.connector.disconnect()
    
    async def run(self):
        """
        Main application flow.
        
        Complete workflow:
        1. Scan for devices
        2. Select device
        3. Connect
        4. Interactive command loop
        5. Disconnect
        """
        print("\n" + "="*60)
        print("BLUETOOTH REMOTE CONTROL FOR ANDROID")
        print("="*60)
        print("\nThis tool allows you to control your Android phone via BLE.")
        print("Make sure the Android app is installed and running!\n")
        print("Available commands:")
        print("  toast <msg>           - Show toast")
        print("  app <pkg>             - Open app")
        print("  log <msg>             - Log message")
        print("  status                - Get status")
        print("  list [path]           - List files")
        print("  sms <num> <msg>       - Send SMS")
        print("  volume <action> [val] - Control volume")
        print("  quit                  - Exit\n")
        
        try:
            # Step 1: Scan and select device
            if not await self.scan_and_select_device():
                return
            
            # Step 2: Connect to device
            if not await self.connect_to_device():
                return
            
            # Step 3: Interactive command loop
            await self.interactive_command_loop()
            
        finally:
            # Step 4: Cleanup
            await self.disconnect()
            print("\n✓ Disconnected. Goodbye!\n")


async def main():
    """Entry point."""
    app = BluetoothRemoteControl()
    await app.run()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n\nExiting...")
        sys.exit(0)
