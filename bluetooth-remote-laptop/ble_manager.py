import asyncio
import logging
from typing import Optional, Dict, List, Callable, Any
from bleak.backends.device import BLEDevice

from ble_scanner import BLEDeviceScanner
from ble_connector import BLEConnector
from command_sender import CommandSender

logger = logging.getLogger(__name__)

class BLEManager:
    """
    Headless BLE Manager for Web Interface.
    
    Manages scanning, connection, and command execution without CLI interactions.
    Broadcasts updates via callbacks (for WebSockets).
    """
    
    def __init__(self):
        self.scanner = BLEDeviceScanner()
        self.connector: Optional[BLEConnector] = None
        self.command_sender: Optional[CommandSender] = None
        self.selected_device: Optional[BLEDevice] = None
        
        # Callbacks for real-time updates
        self.on_status_change: Optional[Callable[[str, Any], None]] = None
        self.on_log: Optional[Callable[[str], None]] = None
        self.on_notification: Optional[Callable[[Any], None]] = None
        
        # Chunk reassembly buffer
        self.chunk_buffer = {}
        self.expected_chunks = 0
        self.received_chunks = 0

    def set_callbacks(self, 
                     on_status_change: Callable[[str, Any], None],
                     on_log: Callable[[str], None],
                     on_notification: Callable[[Any], None]):
        self.on_status_change = on_status_change
        self.on_log = on_log
        self.on_notification = on_notification

    async def start_scan(self, duration: float = 5.0) -> List[Dict[str, Any]]:
        """Scan for devices and return list."""
        if self.on_status_change:
            self.on_status_change("scanning", True)
            
        devices = await self.scanner.scan(duration=duration)
        
        result = []
        for dev in devices:
            result.append({
                "name": dev.name or "Unknown",
                "address": dev.address,
                "rssi": dev.rssi if hasattr(dev, 'rssi') else 0
            })
            
        if self.on_status_change:
            self.on_status_change("scanning", False)
            
        return result

    async def connect(self, address: str) -> bool:
        """Connect to a specific device by address."""
        # We need to find the device object again or cache it.
        # For simplicity, we'll rescan quickly or assume address is valid if we cached it.
        # But bleak needs the BLEDevice object usually (especially on Mac), on Linux address string often works.
        # Let's try to find it in scanner's cache if possible, or rescan.
        
        device = None
        # Check last scanned devices
        for d in self.scanner.discovered_devices:
            if d.address == address:
                device = d
                break
        
        if not device:
            # Quick scan to find it
            found = await self.scanner.scan(duration=2.0)
            for d in found:
                if d.address == address:
                    device = d
                    break
        
        if not device:
            if self.on_log: self.on_log(f"Device {address} not found")
            return False

        self.selected_device = device
        self.connector = BLEConnector(device)
        
        if self.on_status_change:
            self.on_status_change("connecting", True)
            
        success = await self.connector.connect()
        
        if success:
            # MTU is negotiated inside connector.connect()
            await self.connector.start_notify(self._notification_handler)
            self.command_sender = CommandSender(self.connector)
            if self.on_status_change:
                self.on_status_change("connected", True)
                self.on_status_change("device_name", device.name)
        else:
            if self.on_status_change:
                self.on_status_change("connected", False)
                
        if self.on_status_change:
            self.on_status_change("connecting", False)
            
        return success

    async def disconnect(self):
        if self.connector:
            await self.connector.stop_notify()
            await self.connector.disconnect()
            self.connector = None
            self.command_sender = None
            if self.on_status_change:
                self.on_status_change("connected", False)

    def _notification_handler(self, sender: int, data: bytearray):
        """Handle incoming notifications with chunk reassembly."""
        try:
            message = data.decode('utf-8')
            
            # Chunking logic
            if message.startswith("CHUNK:"):
                try:
                    header_end = message.find(":", 6)
                    if header_end != -1:
                        header = message[6:header_end]
                        seq_str, total_str = header.split("/")
                        seq = int(seq_str)
                        total = int(total_str)
                        chunk_data = message[header_end+1:]
                        
                        # Debug log
                        if self.on_log:
                            self.on_log(f"Received chunk {seq}/{total}")
                        
                        if seq == 1:
                            self.chunk_buffer = {}
                            self.expected_chunks = total
                            self.received_chunks = 0
                        
                        self.chunk_buffer[seq] = chunk_data
                        self.received_chunks += 1
                        
                        if self.received_chunks == self.expected_chunks:
                            if self.on_log:
                                self.on_log("Reassembly complete. Processing...")
                            
                            full_message = ""
                            for i in range(1, self.expected_chunks + 1):
                                full_message += self.chunk_buffer.get(i, "")
                            
                            self._process_message(full_message)
                            self.chunk_buffer = {}
                            self.expected_chunks = 0
                        return
                except Exception as e:
                    logger.error(f"Chunk error: {e}")
                    if self.on_log: self.on_log(f"Chunk error: {e}")
                    return

            self._process_message(message)
            
        except Exception as e:
            logger.error(f"Notification error: {e}")
            if self.on_log: self.on_log(f"Notification error: {e}")

    def _process_message(self, message: str):
        """Process complete message and notify frontend."""
        import json
        try:
            # Try to parse as JSON
            data = json.loads(message)
            if self.on_notification:
                self.on_notification(data)
        except:
            # Raw string
            if self.on_notification:
                self.on_notification({"type": "raw", "data": message})

    # Command wrappers
    async def send_command(self, cmd_type: str, payload: Dict[str, Any] = None):
        if not self.command_sender:
            return False
        
        if cmd_type == "list_files":
            return await self.command_sender.list_files(payload.get("path", "/sdcard"))
        elif cmd_type == "open_file":
            return await self.command_sender.open_file(payload.get("path"))
        elif cmd_type == "copy_file":
            return await self.command_sender.copy_file(payload.get("source"), payload.get("dest"))
        elif cmd_type == "read_file":
            return await self.command_sender.read_file(payload.get("path"))
        elif cmd_type == "list_sms":
            return await self.command_sender.list_sms(payload.get("limit", 50))
        elif cmd_type == "send_sms":
            return await self.command_sender.send_sms(payload.get("number"), payload.get("message"))
            
    def _process_message(self, message: str):
        """Process complete message and notify frontend."""
        import json
        import base64
        import os
        
        try:
            # Try to parse as JSON
            data = json.loads(message)
            
            # Handle file download
            if data.get("status") == "file_data":
                try:
                    file_path = data.get("message", {}).get("path") # Wait, check CommandExecutor structure
                    # CommandExecutor sends: {"status": "file_data", "message": "{\"path\":..., \"data\":...}"}
                    # Because sendResponse wraps it in "message".
                    
                    inner_msg = data.get("message")
                    if isinstance(inner_msg, str):
                        inner_data = json.loads(inner_msg)
                    else:
                        inner_data = inner_msg # Should not happen with current Android code
                        
                    path = inner_data.get("path")
                    b64_data = inner_data.get("data")
                    
                    filename = os.path.basename(path)
                    save_path = os.path.join("web/static/downloads", filename)
                    
                    with open(save_path, "wb") as f:
                        f.write(base64.b64decode(b64_data))
                        
                    if self.on_notification:
                        self.on_notification({
                            "type": "file_ready",
                            "url": f"/static/downloads/{filename}",
                            "filename": filename
                        })
                    return
                except Exception as e:
                    logger.error(f"Error saving file: {e}")
                    if self.on_log: self.on_log(f"Error saving file: {e}")
            
            if self.on_notification:
                self.on_notification(data)
        except:
            # Raw string
            if self.on_notification:
                self.on_notification({"type": "raw", "data": message})
        elif cmd_type == "toast":
            return await self.command_sender.show_toast(payload.get("message"))
        elif cmd_type == "app":
            return await self.command_sender.open_app(payload.get("package"))
        elif cmd_type == "volume":
            action = payload.get("action")
            value = payload.get("value", -1)
            if action == "set":
                return await self.command_sender.audio_control("set_volume", value)
            elif action == "up":
                return await self.command_sender.audio_control("volume_up")
            elif action == "down":
                return await self.command_sender.audio_control("volume_down")
        elif cmd_type == "status":
            return await self.command_sender.get_status()
            
        return False
