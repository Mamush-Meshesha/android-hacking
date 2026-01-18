package com.example.btremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boot Receiver
 *
 * Listens for system boot completion and starts the BluetoothService.
 * This ensures the remote control server starts automatically when phone restarts.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BTRemote-Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, starting service...")
            
            val serviceIntent = Intent(context, BluetoothService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
