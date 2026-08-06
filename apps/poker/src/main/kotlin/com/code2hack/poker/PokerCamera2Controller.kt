package com.code2hack.poker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.hardware.camera2.CaptureFailure
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** Camera2-only capture path. The JPEG from ImageReader is passed through byte-for-byte. */
internal class PokerCamera2Controller(
    private val activity: Activity,
    private val onPermissionRequired: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val manager = activity.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextureView? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String? = null
    private var sensorOrientation = 270
    private var zoom = 1f
    private var capture: CancellableContinuation<ByteArray?>? = null

    fun attach(textureView: TextureView) {
        if (view === textureView) {
            if (textureView.isAvailable) open()
            return
        }
        view = textureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                open()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                close()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }
        if (textureView.isAvailable) open()
    }

    fun open() {
        if (camera != null) return
        if (view?.isAvailable != true) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onPermissionRequired()
            return
        }
        val selected = runCatching { selectCamera() }.getOrElse {
            onFailure(it.message ?: "Camera is unavailable")
            return
        }
        cameraId = selected.first
        characteristics = selected.second
        sensorOrientation = selected.second.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        runCatching {
            manager.openCamera(cameraId!!, callback, handler)
        }.onFailure { onFailure(it.message ?: "Camera could not be opened") }
    }

    fun close() {
        capture?.takeIf { it.isActive }?.resume(null)
        capture = null
        session?.close()
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
    }

    fun setZoom(value: Float) {
        zoom = value.coerceIn(1f, 8f)
        applyPreview()
    }

    suspend fun capture(): ByteArray? = suspendCancellableCoroutine { continuation ->
        if (camera == null || session == null || reader == null || capture != null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        capture = continuation
        continuation.invokeOnCancellation { if (capture === continuation) capture = null }
        runCatching {
            val request = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.SCALER_CROP_REGION, cropRegion())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }.build()
            session!!.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    capture?.takeIf { it.isActive }?.resume(null)
                    capture = null
                }
            }, handler)
        }.onFailure {
            capture?.takeIf { it.isActive }?.resume(null)
            capture = null
        }
    }

    private val callback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            camera = device
            createSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (camera === device) camera = null
            onFailure("Camera disconnected")
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (camera === device) camera = null
            onFailure("Camera error $error")
        }
    }

    private fun createSession(device: CameraDevice) {
        val texture = view?.surfaceTexture ?: run {
            onFailure("Camera preview is unavailable")
            return
        }
        texture.setDefaultBufferSize(480, 640)
        val preview = Surface(texture)
        val imageReader = ImageReader.newInstance(4032, 3024, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                val buffer = it.planes.firstOrNull()?.buffer ?: return@use
                val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                capture?.takeIf { continuation -> continuation.isActive }?.resume(bytes)
                capture = null
            }
        }, handler)
        previewSurface = preview
        reader = imageReader
        device.createCaptureSession(
            listOf(preview, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) {
                    session = created
                    applyPreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onFailure("Camera preview could not be configured")
                }
            },
            handler,
        )
    }

    private fun applyPreview() {
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = previewSurface ?: return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.SCALER_CROP_REGION, cropRegion())
            }.build()
            currentSession.setRepeatingRequest(request, null, handler)
        }.onFailure { onFailure(it.message ?: "Camera preview failed") }
    }

    private fun cropRegion(): Rect {
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return Rect(0, 0, 1, 1)
        val width = (active.width() / zoom).toInt().coerceAtLeast(1)
        val height = (active.height() / zoom).toInt().coerceAtLeast(1)
        val left = active.centerX() - width / 2
        val top = active.centerY() - height / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun selectCamera(): Pair<String, CameraCharacteristics> {
        manager.cameraIdList.forEach { id ->
            val candidate = manager.getCameraCharacteristics(id)
            if (candidate.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@forEach
            }
            val map = candidate.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@forEach
            val hasJpeg = map.getOutputSizes(ImageFormat.JPEG).any { it.width == 4032 && it.height == 3024 }
            val hasPreview = map.getOutputSizes(TextureView::class.java)
                .any { it.width == 480 && it.height == 640 }
            if (hasJpeg && hasPreview && candidate.get(CameraCharacteristics.SENSOR_ORIENTATION) == 270) {
                return id to candidate
            }
        }
        error("No back Camera2 sensor supports 480x640 preview and 4032x3024 JPEG")
    }
}
