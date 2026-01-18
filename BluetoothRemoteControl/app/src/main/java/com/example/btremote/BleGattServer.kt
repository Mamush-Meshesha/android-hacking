package com.example.btremote

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.*

/**
 * BLE GATT Server Manager
 *
 * This class manages the BLE GATT server that advertises our service
 * and handles incoming connections and characteristic writes.
 *
 * GATT Server Lifecycle:
 * 1. Open GATT server (BluetoothManager.openGattServer)
 * 2. Add service with characteristics
 * 3. Start advertising (so laptops can discover us)
 * 4. Handle connection callbacks
 * 5. Handle characteristic read/write callbacks
 * 6. Send notifications when needed
 *
 * Under the hood:
 * - Android BLE stack manages connection state
 * - Advertising packets broadcast our service UUID
 * - GATT server callbacks run on Binder thread (not main thread!)
 * - Must post UI updates to main thread
 */
class BleGattServer(
    private val context: Context,
    private val commandCallback: (String) -> Unit
) {
    companion object {
        private const val TAG = "BTRemote-GATT"
        
        // UUIDs must match Python app
        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val COMMAND_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        val RESPONSE_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
        
        private const val DEVICE_NAME = "AndroidRemote"
    }
    
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    
    private var connectedDevice: BluetoothDevice? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    
    /**
     * GATT Server Callback
     *
     * These callbacks are invoked by the Android BLE stack when:
     * - A device connects/disconnects
     * - A characteristic is read
     * - A characteristic is written
     * - A notification is sent
     *
     * CRITICAL: These run on a Binder thread, NOT the main thread!
     * Any UI updates must be posted to main thread.
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Device connected: ${device?.address}")
                    connectedDevice = device
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Device disconnected: ${device?.address}")
                    connectedDevice = null
                }
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            
            Log.d(TAG, "Read request for characteristic: ${characteristic?.uuid}")
            
            when (characteristic?.uuid) {
                RESPONSE_CHAR_UUID -> {
                    // Return current response value
                    val value = characteristic.value ?: byteArrayOf()
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
                else -> {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device, requestId, characteristic, preparedWrite, responseNeeded, offset, value
            )
            
            Log.d(TAG, "Write request for characteristic: ${characteristic?.uuid}")
            
            when (characteristic?.uuid) {
                COMMAND_CHAR_UUID -> {
                    // Received command from laptop
                    value?.let { bytes ->
                        try {
                            val jsonCommand = String(bytes, Charsets.UTF_8)
                            Log.i(TAG, "Received command: $jsonCommand")
                            
                            // Pass to command handler
                            // This callback runs on Binder thread, handler must handle threading
                            commandCallback(jsonCommand)
                            
                            // Send success response
                            if (responseNeeded) {
                                gattServer?.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_SUCCESS,
                                    offset,
                                    value
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing command", e)
                            if (responseNeeded) {
                                gattServer?.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_FAILURE,
                                    offset,
                                    null
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null
                        )
                    }
                }
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(
                device, requestId, descriptor, preparedWrite, responseNeeded, offset, value
            )
            
            // This is called when laptop subscribes to notifications
            // Descriptor UUID is the CCCD (Client Characteristic Configuration Descriptor)
            Log.d(TAG, "Descriptor write request: ${descriptor?.uuid}")
            
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }
    }
    
    /**
     * Advertising Callback
     *
     * Monitors advertising state.
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.i(TAG, "Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "Advertising failed with error code: $errorCode")
            when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> Log.e(TAG, "Already advertising")
                ADVERTISE_FAILED_DATA_TOO_LARGE -> Log.e(TAG, "Advertise data too large")
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "Advertising not supported")
                ADVERTISE_FAILED_INTERNAL_ERROR -> Log.e(TAG, "Internal error")
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> Log.e(TAG, "Too many advertisers")
            }
        }
    }
    
    /**
     * Start GATT server and advertising.
     *
     * Steps:
     * 1. Open GATT server
     * 2. Create service with characteristics
     * 3. Add service to GATT server
     * 4. Start BLE advertising
     *
     * Returns true if started successfully.
     */
    fun start(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return false
        }
        
        try {
            // Step 1: Open GATT server
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                return false
            }
            Log.i(TAG, "GATT server opened")
            
            // Step 2: Create service
            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            
            // Create command characteristic (WRITE)
            val commandCharacteristic = BluetoothGattCharacteristic(
                COMMAND_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(commandCharacteristic)
            
            // Create response characteristic (READ + NOTIFY)
            responseCharacteristic = BluetoothGattCharacteristic(
                RESPONSE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            // Add CCCD descriptor for notifications
            val cccdDescriptor = BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // Standard CCCD UUID
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            responseCharacteristic?.addDescriptor(cccdDescriptor)
            service.addCharacteristic(responseCharacteristic!!)
            
            // Step 3: Add service to GATT server
            val serviceAdded = gattServer?.addService(service) ?: false
            if (!serviceAdded) {
                Log.e(TAG, "Failed to add service")
                return false
            }
            Log.i(TAG, "Service added to GATT server")
            
            // Step 4: Start advertising
            startAdvertising()
            
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - missing Bluetooth permissions", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GATT server", e)
            return false
        }
    }
    
    /**
     * Start BLE advertising.
     *
     * Advertising broadcasts our service UUID so laptops can discover us.
     *
     * Advertising packet structure:
     * - Flags (mandatory)
     * - Service UUIDs (our custom UUID)
     * - Local name (optional, "AndroidRemote")
     */
    private fun startAdvertising() {
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BLE advertising not supported on this device")
            return
        }
        
        // Configure advertising settings
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // Balance power/latency
            .setConnectable(true) // Allow connections
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        
        // Configure advertising data
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // We'll set custom name
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID)) // Our service UUID
            .build()
        
        // Configure scan response (includes device name)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        
        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            Log.i(TAG, "Started advertising with service UUID: $SERVICE_UUID")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - missing BLUETOOTH_ADVERTISE permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting advertising", e)
        }
    }
    
    /**
     * Stop GATT server and advertising.
     */
    fun stop() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
            gattServer = null
            connectedDevice = null
            Log.i(TAG, "GATT server stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while stopping", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GATT server", e)
        }
    }
    
    /**
     * Send notification to connected device.
     *
     * This pushes data to the laptop without waiting for a read request.
     *
     * @param message Message to send
     */
    fun sendNotification(message: String) {
        val device = connectedDevice
        val characteristic = responseCharacteristic
        
        if (device == null) {
            Log.w(TAG, "No device connected, cannot send notification")
            return
        }
        
        if (characteristic == null) {
            Log.e(TAG, "Response characteristic not initialized")
            return
        }
        
        try {
            val bytes = message.toByteArray(Charsets.UTF_8)
            characteristic.value = bytes
            
            val success = gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            if (success == true) {
                Log.i(TAG, "Notification sent: $message")
            } else {
                Log.w(TAG, "Failed to send notification")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while sending notification", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending notification", e)
        }
    }
    
    /**
     * Check if a device is currently connected.
     */
    fun isConnected(): Boolean = connectedDevice != null
}
