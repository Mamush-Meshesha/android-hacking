package com.example.btremote

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotManager(private val context: Context) {
    private val TAG = "ScreenshotManager"
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
    }

    fun requestScreenshotPermission(activity: Activity) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    fun initMediaProjection(resultCode: Int, data: android.content.Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            Log.i(TAG, "MediaProjection initialized")
        } else {
            Log.e(TAG, "MediaProjection permission denied")
        }
    }

    fun takeScreenshot(callback: (File?) -> Unit) {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized. Request permission first.")
            callback(null)
            return
        }

        try {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            // Wait a bit for the display to be ready
            Thread.sleep(100)

            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image, width, height)
                image.close()

                val photoFile = File(
                    context.cacheDir,
                    "screenshot_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                        .format(System.currentTimeMillis()) + ".png"
                )

                FileOutputStream(photoFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                Log.i(TAG, "Screenshot saved: ${photoFile.absolutePath}")
                cleanup()
                callback(photoFile)
            } else {
                Log.e(TAG, "Failed to acquire image")
                cleanup()
                callback(null)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            cleanup()
            callback(null)
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }
    }

    private fun cleanup() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    fun shutdown() {
        cleanup()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
