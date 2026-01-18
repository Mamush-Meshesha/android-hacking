package com.example.btremote

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {
    private val TAG = "AudioRecorder"
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun startRecording(): String? {
        if (mediaRecorder != null) return null

        val outputDir = context.cacheDir
        currentFile = File.createTempFile("recording_", ".m4a", outputDir)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(currentFile?.absolutePath)

            try {
                prepare()
                start()
                Log.i(TAG, "Recording started: ${currentFile?.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "prepare() failed", e)
                return null
            } catch (e: IllegalStateException) {
                Log.e(TAG, "start() failed", e)
                return null
            }
        }

        return currentFile?.absolutePath
    }

    fun stopRecording(): String? {
        val path = currentFile?.absolutePath
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed", e)
        } finally {
            mediaRecorder = null
        }
        return path
    }
}
