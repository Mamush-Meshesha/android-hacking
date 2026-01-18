package com.example.btremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Bluetooth Foreground Service
 *
 * This service keeps the BLE GATT server running in the background.
 * It shows a persistent notification to ensure the system doesn't kill it.
 */
class BluetoothService : Service() {

    companion object {
        private const val TAG = "BTRemote-Service"
        private const val CHANNEL_ID = "BluetoothRemoteChannel"
        private const val NOTIFICATION_ID = 1
        
        // Action to stop service from notification
        const val ACTION_STOP_SERVICE = "com.example.btremote.STOP_SERVICE"
    }

    private var gattServer: BleGattServer? = null
    private var commandExecutor: CommandExecutor? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        startGattServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        // If system kills service, restart it
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't support binding
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        stopGattServer()
    }

    private fun startGattServer() {
        Log.i(TAG, "Starting GATT server in service...")
        
        // Create command executor
        commandExecutor = CommandExecutor(this) { response ->
            // Response callback - send notification to laptop
            gattServer?.sendNotification(response)
        }
        
        // Create GATT server
        gattServer = BleGattServer(this) { jsonCommand ->
            // Command callback - execute command
            commandExecutor?.execute(jsonCommand)
        }
        
        // Start server
        val success = gattServer?.start() ?: false
        
        if (success) {
            Log.i(TAG, "GATT server started successfully")
        } else {
            Log.e(TAG, "Failed to start GATT server")
            // If we can't start Bluetooth, we might as well stop the service
            stopSelf()
        }
    }

    private fun stopGattServer() {
        gattServer?.stop()
        gattServer = null
        commandExecutor = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Bluetooth Remote Service"
            val descriptionText = "Keeps Bluetooth server running"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        
        val stopIntent = Intent(this, BluetoothService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth Remote Active")
            .setContentText("Listening for commands...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
