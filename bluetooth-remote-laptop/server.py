import asyncio
import logging
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.requests import Request
from pydantic import BaseModel
from typing import Optional, Dict, Any

# Import ConnectionManager
from connection_manager import ConnectionManager

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("Server")

app = FastAPI()

# Mount static files
app.mount("/static", StaticFiles(directory="web/static"), name="static")

# Templates
templates = Jinja2Templates(directory="web/templates")

# Initialize Manager
manager = ConnectionManager()

# Zeroconf Service Registration
try:
    from zeroconf import ServiceInfo, Zeroconf
    ZEROCONF_AVAILABLE = True
except ImportError:
    ZEROCONF_AVAILABLE = False
    logger.warning("zeroconf module not found. Auto-discovery disabled.")

import socket

zeroconf = None

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # doesn't even have to be reachable
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

@app.on_event("startup")
async def startup_event():
    # Ensure download directory exists
    import os
    os.makedirs("web/static/downloads", exist_ok=True)
    
    global zeroconf
    if not ZEROCONF_AVAILABLE:
        return
        
    try:
        ip = get_local_ip()
        logger.info(f"Starting server on {ip}:8000")
        
        desc = {'path': '/ws/device'}
        info = ServiceInfo(
            "_btremote._tcp.local.",
            "BTRemote._btremote._tcp.local.",
            addresses=[socket.inet_aton(ip)],
            port=8000,
            properties=desc,
            server="btremote.local."
        )
        
        zeroconf = Zeroconf()
        zeroconf.register_service(info)
        logger.info("mDNS Service registered: _btremote._tcp.local.")
    except Exception as e:
        logger.error(f"Failed to register mDNS service: {type(e).__name__}: {e}")

@app.on_event("shutdown")
async def shutdown_event():
    if zeroconf:
        zeroconf.close()

class CommandRequest(BaseModel):
    type: str
    payload: Optional[Dict[str, Any]] = {}

@app.get("/")
async def get(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """WebSocket for Web Clients (Browser)."""
    await manager.connect_client(websocket)
    try:
        while True:
            # Keep alive / listen for client messages (if any)
            data = await websocket.receive_text()
            # Currently client doesn't send much via WS, mostly API calls
    except WebSocketDisconnect:
        manager.disconnect_client(websocket)

@app.websocket("/ws/device")
async def websocket_device_endpoint(websocket: WebSocket):
    """WebSocket for Android Device."""
    await manager.connect_device(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            await manager.handle_device_message(data)
    except WebSocketDisconnect:
        manager.disconnect_device()

@app.post("/api/command")
async def send_command(cmd: CommandRequest):
    """Send command to connected device (via WebSocket)."""
    if not manager.device_ws:
        raise HTTPException(status_code=503, detail="Device not connected")
    
    success = await manager.send_command({
        "type": cmd.type,
        "payload": cmd.payload
    })
    
    return {"success": success}

@app.post("/api/scan")
async def scan():
    """Check for connected devices (WiFi)."""
    devices = []
    if manager.device_ws:
        devices.append({
            "name": "Android Device", 
            "address": "WiFi Connected", 
            "rssi": 0
        })
    return {"devices": devices}

@app.post("/api/connect")
async def connect(device: Dict[str, str]):
    # No-op for WiFi. Device connects to us.
    return {"success": True, "message": "Waiting for device connection..."}

@app.post("/api/disconnect")
async def disconnect():
    # Maybe force disconnect?
    if manager.device_ws:
        await manager.device_ws.close()
        manager.disconnect_device()
    return {"success": True}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
