package com.example.btremote

import org.json.JSONObject
import android.util.Log

/**
 * Command Parser
 *
 * Parses JSON commands received from the laptop.
 *
 * Command structure:
 * {
 *   "type": "command_type",
 *   "payload": { ... },
 *   "timestamp": 1234567890
 * }
 *
 * This class:
 * 1. Validates JSON structure
 * 2. Extracts command type and payload
 * 3. Returns structured Command object
 */
class CommandParser {
    
    companion object {
        private const val TAG = "BTRemote-Parser"
        
        // Command types
        const val CMD_SHOW_TOAST = "show_toast"
        const val CMD_OPEN_APP = "open_app"
        const val CMD_LOG_ACTION = "log_action"
        const val CMD_GET_STATUS = "get_status"
        
        // Extended commands
        const val CMD_LIST_FILES = "list_files"
        const val CMD_SEND_SMS = "send_sms"
        const val CMD_LIST_SMS = "list_sms"
        const val CMD_AUDIO_CONTROL = "audio_control"
        const val CMD_OPEN_FILE = "open_file"
        const val CMD_COPY_FILE = "copy_file"
        const val CMD_READ_FILE = "read_file"
        const val CMD_START_RECORD = "start_record"
        const val CMD_STOP_RECORD = "stop_record"
        const val CMD_TAKE_PHOTO = "take_photo"
        const val CMD_TAKE_SCREENSHOT = "take_screenshot"
    }
    
    /**
     * Represents a parsed command.
     */
    data class Command(
        val type: String,
        val payload: JSONObject,
        val timestamp: Long
    )
    
    /**
     * Parse JSON command string.
     *
     * @param jsonString Raw JSON string from BLE
     * @return Parsed Command object, or null if invalid
     *
     * Under the hood:
     * - JSONObject parses the string
     * - Validates required fields exist
     * - Extracts type, payload, timestamp
     */
    fun parse(jsonString: String): Command? {
        return try {
            val json = JSONObject(jsonString)
            
            // Validate required fields
            if (!json.has("type")) {
                Log.e(TAG, "Missing 'type' field in command")
                return null
            }
            
            val type = json.getString("type")
            val payload = json.optJSONObject("payload") ?: JSONObject()
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            
            Log.i(TAG, "Parsed command: type=$type, timestamp=$timestamp")
            
            Command(type, payload, timestamp)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing command JSON", e)
            null
        }
    }
    
    /**
     * Validate command type.
     */
    fun isValidCommandType(type: String): Boolean {
        return type in listOf(
            CMD_SHOW_TOAST, CMD_OPEN_APP, CMD_LOG_ACTION, CMD_GET_STATUS,
            CMD_LIST_FILES, CMD_SEND_SMS, CMD_LIST_SMS, CMD_AUDIO_CONTROL,
            CMD_OPEN_FILE, CMD_COPY_FILE, CMD_READ_FILE,
            CMD_START_RECORD, CMD_STOP_RECORD,
            CMD_TAKE_PHOTO, CMD_TAKE_SCREENSHOT
        )
    }
}
