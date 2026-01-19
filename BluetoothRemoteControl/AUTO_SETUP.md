# Auto-Permission & Auto-Connect Setup Guide

## Educational Use Only

This guide explains how to set up the app for automatic permission granting and WiFi auto-connect on non-rooted devices.

---

## Features

✅ **Auto-Permission Granting** - Automatically clicks "Allow" on permission dialogs  
✅ **Auto-Connect WiFi** - Saves server IP and connects automatically on launch  
✅ **Persistent Connection** - Maintains connection in background  
✅ **Auto-Reconnect** - Reconnects if connection drops  

---

## Setup Instructions

### Step 1: Build & Install App

```bash
cd BluetoothRemoteControl
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Step 2: Enable Accessibility Service

1. **Open the app** on your phone
2. You'll see a dialog: **"Enable Auto-Permission"**
3. Click **"Open Settings"**
4. Find **"BTRemote"** in the list
5. **Toggle it ON**
6. Confirm the warning dialog

**What this does:**
- Allows the app to detect permission dialogs
- Automatically clicks "Allow" buttons
- No more manual permission granting!

### Step 3: Connect to Server

1. **Start Python server** on your PC:
   ```bash
   cd bluetooth-remote-laptop
   python server.py
   ```

2. **Enter server IP** in the app (e.g., `192.168.100.146`)
3. Click **"Connect WiFi"**

**The IP is saved automatically!**

### Step 4: Test Auto-Features

1. **Close the app** completely
2. **Reopen the app**
3. ✅ **Auto-connects** to saved server IP
4. ✅ **Auto-grants** any new permissions needed

---

## How It Works

### Auto-Permission (Accessibility Service)

The `AutoPermissionService` uses Android's Accessibility API to:
1. Detect when permission dialogs appear
2. Find the "Allow" button
3. Click it automatically

**Supported dialogs:**
- Camera permission
- Microphone permission
- SMS permission
- Storage permission
- Location permission
- All other runtime permissions

### Auto-Connect WiFi

The app saves your server IP in `SharedPreferences`:
- **First time**: Enter IP manually
- **Every launch after**: Auto-connects to saved IP
- **No user interaction needed!**

### Persistent Connection

The WebSocket connection:
- Runs in background
- Survives app minimization
- Auto-reconnects on network changes

---

## Troubleshooting

### "Accessibility Service not enabled"
- Go to Settings → Accessibility
- Find "BTRemote"
- Toggle it ON

### "Auto-permission not working"
- Make sure Accessibility Service is enabled
- Check that the service is running: Settings → Accessibility → BTRemote (should show "On")
- Try disabling and re-enabling the service

### "Auto-connect not working"
- Make sure you connected at least once manually
- Check that server IP is saved (it should auto-fill when you open the app)
- Verify Python server is running

### "Connection keeps dropping"
- Check WiFi signal strength
- Ensure phone isn't in battery saver mode
- Verify firewall allows port 8000

---

## Limitations

### Accessibility Service
- **Requires one-time manual enable** (Android security requirement)
- Cannot be enabled programmatically
- User must approve in Settings

### Auto-Connect
- Requires server to be running
- Only works if phone has network access
- May fail if server IP changes (DHCP)

**Solution**: Use Tailscale for permanent IP addresses (see `TAILSCALE_SETUP.md`)

---

## Privacy & Security

### What the Accessibility Service Can Do
- ✅ Click buttons on permission dialogs
- ✅ Read text from permission dialogs
- ❌ Cannot access other apps' data
- ❌ Cannot perform actions outside permission dialogs

### What Gets Saved
- Server IP address (in app's private storage)
- No passwords or sensitive data

### Permissions Granted
All permissions are legitimate and required for app functionality:
- Camera - for photo capture
- Microphone - for audio recording
- SMS - for reading/sending messages
- Storage - for file access
- etc.

---

## Uninstalling

To completely remove the app:

1. **Disable Accessibility Service**:
   - Settings → Accessibility → BTRemote → Toggle OFF

2. **Uninstall app**:
   - Settings → Apps → BTRemote → Uninstall

All data is removed automatically.

---

## Next Steps

- ✅ App auto-grants permissions
- ✅ App auto-connects to server
- ✅ Connection persists in background

**You're all set!** The app now works with minimal user interaction.

For remote access across different networks, see `TAILSCALE_SETUP.md`.
