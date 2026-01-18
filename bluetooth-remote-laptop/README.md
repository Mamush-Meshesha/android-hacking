# Bluetooth Remote Control - Laptop Side

Python application for controlling Android devices via Bluetooth Low Energy (BLE).

## Overview
# Android Remote Control via WiFi/Internet

Control your Android phone from your PC using a modern web interface. Supports SMS, file browsing, audio recording, and more.

## Features

- 📱 **SMS Management**: Read and send text messages
- 📁 **File Explorer**: Browse and download files from your phone
- 🎤 **Audio Recording**: Record audio remotely and download to PC
- 🔊 **Volume Control**: Adjust phone volume from your PC
- 🌐 **Web Interface**: Modern, OS-like UI accessible from any browser
- 🔒 **Secure**: WiFi/Internet connection with optional VPN support
- 🌍 **Remote Access**: Works across different networks via Tailscale VPN

## Quick Start

### 1. Install Dependencies

```bash
cd bluetooth-remote-laptop
pip install -r requirements.txt
```

### 2. Start the Server

```bash
python server.py
```

Server will start on `http://0.0.0.0:8000`

### 3. Install Android App

1. Open `BluetoothRemoteControl` in Android Studio
2. Build and install on your phone
3. Grant all requested permissions

### 4. Connect

**On the same WiFi network:**
1. Find your PC's IP address: `ip addr show` (look for 192.168.x.x)
2. Open the Android app
3. Enter your PC's IP address
4. Click "Connect WiFi"

**On different networks (Remote Access):**
- See [TAILSCALE_SETUP.md](TAILSCALE_SETUP.md) for complete setup guide
- Enables access from anywhere (phone on mobile data, PC on home WiFi, etc.)
- Secure, encrypted, and easy to set up (5 minutes)

### 5. Use the Web Interface

Open `http://localhost:8000` in your browser to access the control panel.

### Examples

```
> toast Hello from my laptop!
> app com.android.chrome
> log Testing remote control
> status
```

## Common Package Names

- Chrome: `com.android.chrome`
- Settings: `com.android.settings`
- Camera: `com.android.camera2`
- Messages: `com.google.android.apps.messaging`
- Calculator: `com.android.calculator2`

## Troubleshooting

### No devices found

1. **Check Android app is running**
   - Open the Android app
   - Ensure it shows "GATT Server Running"

2. **Check Bluetooth is enabled**
   - On laptop: `bluetoothctl power on`
   - On Android: Settings → Bluetooth → ON

3. **Check permissions**
   - Android app needs Bluetooth permissions
   - On Android 12+, needs BLUETOOTH_ADVERTISE permission

4. **Check range**
   - Devices should be within ~10 meters
   - Remove obstacles between devices

### Connection fails

1. **Unpair and re-pair devices**
   ```bash
   # On Linux
   bluetoothctl
   remove <device_address>
   scan on
   pair <device_address>
   ```

2. **Restart Bluetooth**
   ```bash
   # On Linux
   sudo systemctl restart bluetooth
   ```

3. **Check Android app logs**
   ```bash
   adb logcat | grep BTRemote
   ```

### Commands not working

1. **Check connection status**
   - App should show "Connected" status

2. **Check MTU size**
   - Large commands may need MTU negotiation
   - Default MTU is 23 bytes, can go up to 517

3. **Check Android logs**
   ```bash
   adb logcat | grep BTRemote
   ```

## Technical Details

### BLE GATT Structure

```
Service UUID: 12345678-1234-5678-1234-56789abcdef0
├── Command Characteristic (Write)
│   UUID: 12345678-1234-5678-1234-56789abcdef1
└── Response Characteristic (Read/Notify)
    UUID: 12345678-1234-5678-1234-56789abcdef2
```

### Command Protocol

Commands are JSON objects sent as UTF-8 bytes:

```json
{
  "type": "command_type",
  "payload": {
    "key": "value"
  },
  "timestamp": 1234567890
}
```

### Under the Hood

1. **Scanning**: Listens for BLE advertising packets containing our service UUID
2. **Connection**: Establishes BLE link layer connection
3. **Service Discovery**: Discovers GATT services and characteristics
4. **MTU Negotiation**: Negotiates maximum packet size (23-517 bytes)
5. **Command Transmission**: Writes JSON bytes to command characteristic
6. **Notification**: Receives responses via notification characteristic

## Security Notes

This is a **cooperative control system**, not an exploit:

✅ Requires standard Bluetooth pairing  
✅ Requires Android app running with user consent  
✅ Uses official Android BLE APIs  
✅ No privilege escalation  
✅ Transparent operation  

❌ No exploits or vulnerabilities  
❌ Cannot bypass pairing or permissions  
❌ Not designed for covert operation  

## Development

### Running Individual Modules

```bash
# Test scanner
python ble_scanner.py

# Test connector (requires device from scanner)
python ble_connector.py

# Test command sender (requires connector)
python command_sender.py
```

### Logging

Set log level in code or via environment:

```bash
export LOG_LEVEL=DEBUG
python main.py
```

## License

Educational proof-of-concept. Use responsibly and only on devices you own.
