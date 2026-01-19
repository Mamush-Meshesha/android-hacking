#!/bin/bash

# Quick Permission Grant Script
# For educational use on rooted devices

echo "Granting all permissions to BTRemote..."

adb shell pm grant com.example.btremote android.permission.CAMERA
adb shell pm grant com.example.btremote android.permission.RECORD_AUDIO
adb shell pm grant com.example.btremote android.permission.READ_SMS
adb shell pm grant com.example.btremote android.permission.SEND_SMS
adb shell pm grant com.example.btremote android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.example.btremote android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant com.example.btremote android.permission.READ_CONTACTS
adb shell pm grant com.example.btremote android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.example.btremote android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.example.btremote android.permission.ACCESS_NETWORK_STATE
adb shell pm grant com.example.btremote android.permission.ACCESS_WIFI_STATE
adb shell pm grant com.example.btremote android.permission.CHANGE_WIFI_MULTICAST_STATE

echo "✓ All permissions granted!"
echo ""
echo "Note: These permissions persist until app is uninstalled."
