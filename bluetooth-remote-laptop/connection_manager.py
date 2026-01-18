from typing import List, Optional, Dict, Any
from fastapi import WebSocket
import json
import logging

logger = logging.getLogger("ConnectionManager")

class ConnectionManager:
    def __init__(self):
        # The single active Android device connection
        self.device_ws: Optional[WebSocket] = None
        
        # List of connected Web Clients (Browsers)
        self.active_clients: List[WebSocket] = []

    async def connect_device(self, websocket: WebSocket):
        await websocket.accept()
        self.device_ws = websocket
        logger.info("Device connected via WebSocket")
        await self.broadcast({"type": "status", "status": "connected", "method": "wifi"})

    def disconnect_device(self):
        self.device_ws = None
        logger.info("Device disconnected")
        # Notify clients
        import asyncio
        asyncio.create_task(self.broadcast({"type": "status", "status": "disconnected"}))

    async def connect_client(self, websocket: WebSocket):
        await websocket.accept()
        self.active_clients.append(websocket)
        # Send current status
        status = "connected" if self.device_ws else "disconnected"
        await websocket.send_json({"type": "status", "status": status, "method": "wifi"})

    def disconnect_client(self, websocket: WebSocket):
        if websocket in self.active_clients:
            self.active_clients.remove(websocket)

    async def send_command(self, command: Dict[str, Any]) -> bool:
        """Send a command to the Android device."""
        if not self.device_ws:
            logger.warning("Cannot send command: Device not connected")
            return False
        
        try:
            await self.device_ws.send_json(command)
            return True
        except Exception as e:
            logger.error(f"Error sending command: {e}")
            self.disconnect_device()
            return False

    async def broadcast(self, message: Dict[str, Any]):
        """Broadcast a message to all Web Clients."""
        for connection in self.active_clients:
            try:
                await connection.send_json(message)
            except Exception as e:
                logger.error(f"Error broadcasting to client: {e}")
                self.disconnect_client(connection)

    async def handle_device_message(self, message: str):
        """Handle incoming message from Device (supports chunking)."""
        if not message:
            return

        # Handle BLE-style chunking if present
        if message.startswith("CHUNK:"):
            try:
                # Format: CHUNK:seq/total:data
                parts = message.split(":", 2)
                if len(parts) < 3:
                    return
                
                header = parts[1]
                data = parts[2]
                
                seq_parts = header.split("/")
                seq = int(seq_parts[0])
                total = int(seq_parts[1])
                
                if seq == 1:
                    self.chunk_buffer = data
                else:
                    self.chunk_buffer += data
                
                if seq == total:
                    full_message = self.chunk_buffer
                    self.chunk_buffer = ""
                    await self._process_full_message(full_message)
                return
            except Exception as e:
                logger.error(f"Error reassembling chunk: {e}")
                self.chunk_buffer = ""
                return
        
        # Direct JSON message
        await self._process_full_message(message)

    async def _process_full_message(self, message: str):
        """Process a complete JSON message from the device."""
        try:
            data = json.loads(message)
            
            # Check for file data to save locally (if needed)
            if data.get("status") == "file_data":
                self._save_file(data)
                return

            # Forward to clients with "notification" wrapper to match BLE structure
            await self.broadcast({
                "type": "notification",
                "data": data
            })
                
        except Exception as e:
            logger.error(f"Error parsing device message: {e}\nMessage: {message[:100]}...")

    def _save_file(self, data: Dict[str, Any]):
        import base64
        import os
        try:
            inner_msg = data.get("message")
            if isinstance(inner_msg, str):
                inner_data = json.loads(inner_msg)
            else:
                inner_data = inner_msg
                
            path = inner_data.get("path")
            b64_data = inner_data.get("data")
            
            filename = os.path.basename(path)
            save_path = os.path.join("web/static/downloads", filename)
            
            with open(save_path, "wb") as f:
                f.write(base64.b64decode(b64_data))
                
            # Notify clients with URL
            import asyncio
            asyncio.create_task(self.broadcast({
                "type": "file_ready",
                "url": f"/static/downloads/{filename}",
                "filename": filename
            }))
        except Exception as e:
            logger.error(f"Error saving file: {e}")
