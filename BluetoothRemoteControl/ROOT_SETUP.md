# Root Setup Guide - Educational Use Only

⚠️ **WARNING**: This guide is for educational purposes only. Use only on devices you own.

## Quick Setup (Recommended)

### Option 1: Grant Permissions via ADB (Easiest)

1. **Enable USB Debugging** on your Android device
2. **Connect device** to PC via USB
3. **Run the script**:
   ```bash
   cd BluetoothRemoteControl
   ./grant_permissions.sh
   ```

This grants all permissions automatically. Permissions persist until you uninstall the app.

---

## Advanced Setup (System App)

For **permanent system-level access** (survives reboots, cannot be uninstalled normally):

### Requirements
- Rooted Android device (Magisk recommended)
- ADB access
- USB debugging enabled

### Installation

1. **Build the app**:
   ```bash
   cd BluetoothRemoteControl
   ./gradlew assembleDebug
   ```

2. **Run system installation script**:
   ```bash
   ./install_as_system.sh
   ```

3. **Device will reboot**

4. **After reboot**, the app has system privileges:
   - All permissions granted automatically
   - Cannot be uninstalled via normal means
   - Runs with elevated privileges

### Uninstalling System App

```bash
adb shell
su
mount -o rw,remount /system
rm -rf /system/priv-app/BTRemote
mount -o ro,remount /system
reboot
```

---

## Auto-Connect WiFi

To make the app auto-connect on startup, I can add:

1. **Save WiFi server IP** in SharedPreferences
2. **Auto-connect on app launch**
3. **Reconnect on network change**
4. **Run as foreground service** (persistent)

Would you like me to implement auto-connect functionality?

---

## Magisk Module (Alternative)

For **easier installation** on Magisk-rooted devices, I can create a Magisk module that:
- Installs app as system app
- Grants all permissions automatically
- Survives updates
- Easy to install/uninstall via Magisk Manager

Let me know if you want this approach!

---

## Security Notes

- **Root access** gives full control over your device
- **System apps** can access sensitive data
- **Use only on your own devices**
- **Educational purposes only**

---

## Troubleshooting

### "Device not rooted"
- Install Magisk or another root solution
- Grant root access when prompted

### "Permission denied"
- Enable USB debugging
- Authorize PC in device prompt
- Grant root access to ADB shell

### "App crashes"
- Check logcat: `adb logcat | grep BTRemote`
- Ensure all permissions are granted
- Verify device compatibility

---

## Next Steps

1. Run `./grant_permissions.sh` for quick setup
2. Or run `./install_as_system.sh` for permanent system app
3. Let me know if you want auto-connect functionality!
