package com.example.btremote

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val onMessageReceived: (String) -> Unit,
    private val onConnectionChanged: (Boolean, String?) -> Unit
) {
    private val TAG = "WebSocketManager"
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    
    var isConnected = false
        private set

    fun connect(ip: String, port: Int = 8000) {
        if (isConnected) return

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive
            .build()

        val request = Request.Builder()
            .url("ws://$ip:$port/ws/device")
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to Server")
                isConnected = true
                onConnectionChanged(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: $text")
                onMessageReceived(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing: $code / $reason")
                webSocket.close(1000, null)
                isConnected = false
                onConnectionChanged(false, "Closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Error: " + t.message)
                isConnected = false
                onConnectionChanged(false, t.message)
            }
        })
    }

    fun sendMessage(message: String) {
        webSocket?.send(message)
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        isConnected = false
    }
}
