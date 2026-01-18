package com.example.btremote

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class CameraManager(private val context: Context) {
    private val TAG = "CameraManager"
    private var cameraManager: android.hardware.camera2.CameraManager = 
        context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

    fun takePhoto(useFrontCamera: Boolean, callback: (File?) -> Unit) {
        try {
            val cameraId = getCameraId(useFrontCamera)
            if (cameraId == null) {
                Log.e(TAG, "No suitable camera found")
                callback(null)
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    captureImage(camera, callback)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    callback(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    callback(null)
                }
            }, null)

        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            callback(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            callback(null)
        }
    }

    private fun getCameraId(useFrontCamera: Boolean): String? {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                val targetFacing = if (useFrontCamera) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                
                if (facing == targetFacing) {
                    return cameraId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera ID", e)
        }
        return null
    }

    private fun captureImage(camera: CameraDevice, callback: (File?) -> Unit) {
        try {
            val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
            
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        
                        val photoFile = File(
                            context.cacheDir,
                            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                                .format(System.currentTimeMillis()) + ".jpg"
                        )
                        
                        FileOutputStream(photoFile).use { output ->
                            output.write(bytes)
                        }
                        
                        Log.i(TAG, "Photo saved: ${photoFile.absolutePath}")
                        image.close()
                        reader.close()
                        camera.close()
                        callback(photoFile)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving image", e)
                        image.close()
                        reader.close()
                        camera.close()
                        callback(null)
                    }
                } else {
                    reader.close()
                    camera.close()
                    callback(null)
                }
            }, null)

            val surface = imageReader.surface
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(surface)
            
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.capture(
                                captureBuilder.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        Log.i(TAG, "Capture completed")
                                        session.close()
                                    }

                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: CaptureFailure
                                    ) {
                                        Log.e(TAG, "Capture failed")
                                        session.close()
                                        imageReader.close()
                                        camera.close()
                                        callback(null)
                                    }
                                },
                                null
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error capturing image", e)
                            session.close()
                            imageReader.close()
                            camera.close()
                            callback(null)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera configuration failed")
                        session.close()
                        imageReader.close()
                        camera.close()
                        callback(null)
                    }
                },
                null
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in captureImage", e)
            camera.close()
            callback(null)
        }
    }
}
