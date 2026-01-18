# Bluetooth Remote Control - Android App

Android application that acts as a BLE GATT server to receive commands from a laptop.

## Overview

This app demonstrates legitimate remote control via BLE. It requires explicit user consent and only executes actions the app has permissions for.

## Features

- BLE GATT server with custom service
- Receives JSON commands via BLE
- Executes commands:
  - Show toast notifications
  - Open apps by package name
  - Log messages to logcat
  - Return device status
- Sends responses via BLE notifications

## Requirements

- Android 5.0+ (API 21+)
- Device with BLE support
- Bluetooth enabled

## Building

### Using Android Studio

1. Open Android Studio
2. File → Open → Select `BluetoothRemoteControl` directory
3. Wait for Gradle sync
4. Build → Make Project
5. Run → Run 'app'

### Using Command Line

```bash
cd BluetoothRemoteControl
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Install and launch app**
   - Grant Bluetooth permissions when prompted
   - Grant location permission (Android 11 and below)

2. **Start GATT server**
   - Click "START SERVER" button
   - App will show "GATT Server Running"
   - App is now advertising and waiting for connections

3. **Connect from laptop**
   - Run Python app on laptop
   - Laptop will discover and connect to phone

4. **Receive commands**
   - Commands appear as toasts, app launches, or logs
   - Responses sent back to laptop

## Permissions

### Android 12+ (API 31+)
- `BLUETOOTH_CONNECT` - Required for BLE operations
- `BLUETOOTH_ADVERTISE` - Required for advertising
- `BLUETOOTH_SCAN` - Required for scanning

### Android 11 and below
- `BLUETOOTH` - Basic Bluetooth operations
- `BLUETOOTH_ADMIN` - Administrative operations
- `ACCESS_FINE_LOCATION` - Required for BLE

## Architecture

```
MainActivity
├── BleGattServer (manages BLE server and advertising)
├── CommandExecutor (executes received commands)
└── CommandParser (parses JSON commands)
```

## Technical Details

### BLE GATT Structure

```
Service UUID: 12345678-1234-5678-1234-56789abcdef0
├── Command Characteristic (Write)
│   UUID: 12345678-1234-5678-1234-56789abcdef1
│   Receives JSON commands from laptop
└── Response Characteristic (Read/Notify)
    UUID: 12345678-1234-5678-1234-56789abcdef2
    Sends responses to laptop
```

### Command Format

Commands are JSON objects:

```json
{
  "type": "show_toast",
  "payload": {
    "message": "Hello!"
  },
  "timestamp": 1234567890
}
```

### Supported Commands

1. **show_toast** - Show toast notification
2. **open_app** - Open app by package name
3. **log_action** - Log message to logcat
4. **get_status** - Return device status

## Debugging

### View logs

```bash
# View all app logs
adb logcat | grep BTRemote

# View specific component logs
adb logcat | grep BTRemote-GATT
adb logcat | grep BTRemote-Executor
```

### Common issues

**Server won't start**
- Check Bluetooth is enabled
- Check permissions are granted
- Check device supports BLE

**Laptop can't discover device**
- Ensure server is running (check status text)
- Ensure devices are within range (~10m)
- Check Bluetooth is enabled on laptop
- Try restarting Bluetooth on both devices

**Commands not executing**
- Check logcat for errors
- Verify JSON format is correct
- Check app has necessary permissions

## Security Notes

This app:
✅ Requires user to launch app  
✅ Requires user to grant permissions  
✅ Requires user to start server  
✅ Shows all actions to user  
✅ Only executes permitted actions  

This app does NOT:
❌ Run in background without user knowledge  
❌ Execute privileged operations  
❌ Bypass security restrictions  

## File Structure

```
app/src/main/
├── java/com/example/btremote/
│   ├── MainActivity.kt          - Main UI and lifecycle
│   ├── BleGattServer.kt         - BLE GATT server management
│   ├── CommandParser.kt         - JSON command parsing
│   └── CommandExecutor.kt       - Command execution
├── res/
│   └── layout/
│       └── activity_main.xml    - UI layout
└── AndroidManifest.xml          - App configuration and permissions
```

## License

Educational proof-of-concept. Use responsibly and only on devices you own.
