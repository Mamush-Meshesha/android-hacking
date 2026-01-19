#!/bin/bash

# System App Installation Script for Android Remote Control
# Requires: Rooted Android device with ADB access
# Educational purposes only - use on your own devices

set -e

echo "=========================================="
echo "Android Remote Control - System App Setup"
echo "Educational Use Only"
echo "=========================================="
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device detected. Please connect your device via USB."
    exit 1
fi

echo "✓ Device connected"

# Check if device is rooted
if ! adb shell su -c "echo 'root access'" 2>/dev/null | grep -q "root access"; then
    echo "❌ Device is not rooted or root access denied."
    echo "This script requires root access."
    exit 1
fi

echo "✓ Root access confirmed"

# Build the APK
echo ""
echo "Building APK..."
cd BluetoothRemoteControl
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at $APK_PATH"
    exit 1
fi

echo "✓ APK built successfully"

# Install as system app
echo ""
echo "Installing as system app..."

# Remount system as read-write
adb shell su -c "mount -o rw,remount /system"

# Create app directory in system
adb shell su -c "mkdir -p /system/priv-app/BTRemote"

# Push APK to system
adb push "$APK_PATH" /sdcard/btremote.apk
adb shell su -c "cp /sdcard/btremote.apk /system/priv-app/BTRemote/BTRemote.apk"
adb shell su -c "chmod 644 /system/priv-app/BTRemote/BTRemote.apk"

# Clean up
adb shell rm /sdcard/btremote.apk

# Remount system as read-only
adb shell su -c "mount -o ro,remount /system"

echo "✓ App installed to /system/priv-app/BTRemote/"

# Reboot device
echo ""
echo "Rebooting device..."
adb reboot

echo ""
echo "=========================================="
echo "Installation complete!"
echo "=========================================="
echo ""
echo "After reboot, the app will have system-level privileges."
echo "All permissions will be granted automatically."
echo ""
echo "Note: To uninstall, you'll need to manually remove:"
echo "  /system/priv-app/BTRemote/"
echo "=========================================="
