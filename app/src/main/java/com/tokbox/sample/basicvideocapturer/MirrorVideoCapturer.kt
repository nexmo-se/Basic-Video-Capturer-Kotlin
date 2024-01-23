package com.tokbox.sample.basicvideocapturer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.hardware.Camera.PreviewCallback
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.opentok.android.BaseVideoCapturer
import com.opentok.android.BaseVideoCapturer.CaptureSwitch
import com.opentok.android.Publisher
import com.opentok.android.Publisher.CameraCaptureFrameRate
import com.opentok.android.Publisher.CameraCaptureResolution
import com.opentok.android.VideoUtils
import java.util.Collections
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class MirrorVideoCapturer(
    context: Context, resolution: CameraCaptureResolution,
    _fps: CameraCaptureFrameRate
) : BaseVideoCapturer(), PreviewCallback, CaptureSwitch {
    private var cameraIndex = 0
    private var camera: Camera? = null
    private var currentDeviceInfo: CameraInfo? = null
    var previewBufferLock = ReentrantLock()
    var pixelFormat = PixelFormat()

    // True when the C++ layer has ordered the camera to be started.
    private var isCaptureStarted = false
    private var isCaptureRunning = false
    private val numCaptureBuffers = 3
    private var expectedFrameSize = 0
    private var captureWidth = -1
    private var captureHeight = -1
    private lateinit var captureFpsRange: IntArray
    private val currentDisplay: Display
    private var surfaceTexture: SurfaceTexture? = null
    private var publisher: Publisher? = null
    private var blackFrames = false
    private var isCapturePaused = false
    private var preferredResolution = CameraCaptureResolution.MEDIUM
    private var preferredFrameRate = CameraCaptureFrameRate.FPS_30

    //default case
    var fps = 1
    var width = 0
    var height = 0
    var frame: IntArray? = null
    var handler = Handler()
    var newFrame: Runnable = object : Runnable {
        override fun run() {
            if (isCaptureRunning) {
                if (frame == null) {
                    var resolution = VideoUtils.Size()
                    resolution = getPreferredResolution()
                    fps = getPreferredFrameRate()
                    width = resolution.width
                    height = resolution.height
                    frame = IntArray(width * height)
                }
                provideIntArrayFrame(frame, ARGB, width, height, 0, false)
                handler.postDelayed(this, (1000 / fps).toLong())
            }
        }
    }


    init {
        cameraIndex = getCameraIndexUsingFront(true)

        // Get current display to query UI orientation
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        currentDisplay = windowManager.defaultDisplay
        preferredFrameRate = _fps
        preferredResolution = resolution
    }

    @Synchronized
    override fun init() {
        Log.d(TAG, "init() enetered")
        try {
            camera = Camera.open(cameraIndex)
        } catch (exp: RuntimeException) {
            Log.e(TAG, "The camera is in use by another app")
        }
        currentDeviceInfo = CameraInfo()
        Camera.getCameraInfo(cameraIndex, currentDeviceInfo)
        Log.d(TAG, "init() exit")
    }

    @Synchronized
    override fun startCapture(): Int {
        Log.d(TAG, "started() entered")
        if (isCaptureStarted) {
            return -1
        }
        if (camera != null) {
            //check preferredResolution and preferredFrameRate values
            val resolution = getPreferredResolution()
            configureCaptureSize(resolution.width, resolution.height)
            val parameters = camera!!.parameters
            parameters.setPreviewSize(captureWidth, captureHeight)
            parameters.previewFormat = PIXEL_FORMAT
            parameters.setPreviewFpsRange(captureFpsRange[0], captureFpsRange[1])
            val focusModes = parameters.supportedFocusModes
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            try {
                camera!!.parameters = parameters
            } catch (exp: RuntimeException) {
                Log.e(TAG, "Camera.setParameters(parameters) - failed")
                return -1
            }

            // Create capture buffers
            PixelFormat.getPixelFormatInfo(PixelFormat.RGBA_8888, pixelFormat)
            val bufSize = (captureWidth * captureHeight * pixelFormat.bitsPerPixel
                    / 8)
            var buffer: ByteArray? = null
            for (i in 0 until numCaptureBuffers) {
                buffer = ByteArray(bufSize)
                camera!!.addCallbackBuffer(buffer)
            }
            try {
                surfaceTexture = SurfaceTexture(42)
                camera!!.setPreviewTexture(surfaceTexture)
            } catch (e: Exception) {
                e.printStackTrace()
                return -1
            }
            camera!!.setPreviewCallbackWithBuffer(this)
            camera!!.startPreview()
            previewBufferLock.lock()
            expectedFrameSize = bufSize
            previewBufferLock.unlock()
        } else {
            blackFrames = true
            handler.postDelayed(newFrame, (1000 / fps).toLong())
        }
        isCaptureRunning = true
        isCaptureStarted = true
        Log.d(TAG, "started() exit")
        return 0
    }

    @Synchronized
    override fun stopCapture(): Int {
        if (camera != null) {
            previewBufferLock.lock()
            try {
                if (isCaptureRunning) {
                    isCaptureRunning = false
                    camera!!.stopPreview()
                    camera!!.setPreviewCallbackWithBuffer(null)
                    camera!!.release()
                    Log.d(TAG, "Camera capture is stopped")
                }
            } catch (exp: RuntimeException) {
                Log.e(TAG, "Camera.stopPreview() - failed ")
                return -1
            }
            previewBufferLock.unlock()
        }
        isCaptureStarted = false
        if (blackFrames) {
            handler.removeCallbacks(newFrame)
        }
        return 0
    }

    override fun destroy() {}
    override fun isCaptureStarted(): Boolean {
        return isCaptureStarted
    }

    override fun getCaptureSettings(): CaptureSettings {
        var captureSettings = CaptureSettings()
        var resolution = VideoUtils.Size()
        resolution = getPreferredResolution()
        val frameRate = getPreferredFrameRate()
        if (camera != null) {
            captureSettings = CaptureSettings()
            configureCaptureSize(resolution.width, resolution.height)
            captureSettings.fps = frameRate
            captureSettings.width = captureWidth
            captureSettings.height = captureHeight
            captureSettings.format = NV21
            captureSettings.expectedDelay = 0
        } else {
            captureSettings.fps = frameRate
            captureSettings.width = resolution.width
            captureSettings.height = resolution.height
            captureSettings.format = ARGB
        }
        return captureSettings
    }

    @Synchronized
    override fun onPause() {
        if (isCaptureStarted) {
            isCapturePaused = true
            stopCapture()
        }
    }

    override fun onResume() {
        if (isCapturePaused) {
            init()
            startCapture()
            isCapturePaused = false
        }
    }

    private val naturalCameraOrientation: Int
        private get() = if (currentDeviceInfo != null) {
            currentDeviceInfo!!.orientation
        } else {
            0
        }
    val isFrontCamera: Boolean
        get() {
            return if (currentDeviceInfo != null) {
                currentDeviceInfo!!.facing == CameraInfo.CAMERA_FACING_FRONT
            } else false
        }

    override fun getCameraIndex(): Int {
        return cameraIndex
    }

    @Synchronized
    override fun cycleCamera() {
        swapCamera((getCameraIndex() + 1) % Camera.getNumberOfCameras())
    }

    @SuppressLint("DefaultLocale")
    @Synchronized
    override fun swapCamera(index: Int) {
        val wasStarted = isCaptureStarted
        if (camera != null) {
            stopCapture()
            camera!!.release()
            camera = null
        }
        cameraIndex = index
        if (wasStarted) {
            if (-1 != Build.MODEL.lowercase(Locale.getDefault()).indexOf("samsung")) {
                /* This was added to workaround a bug on some Samsung devices (OPENTOK-25126)
                 * but it introduces a bug on the Nexus 7 & 9 (OPENTOK-29246) so.... */
                forceCameraRelease(index)
            }
            camera = Camera.open(index)
            currentDeviceInfo = CameraInfo()
            Camera.getCameraInfo(index, currentDeviceInfo)
            startCapture()
        }
    }

    private fun compensateCameraRotation(uiRotation: Int): Int {
        var currentDeviceOrientation = 0
        when (uiRotation) {
            Surface.ROTATION_0 -> currentDeviceOrientation = 0
            Surface.ROTATION_90 -> currentDeviceOrientation = 270
            Surface.ROTATION_180 -> currentDeviceOrientation = 180
            Surface.ROTATION_270 -> currentDeviceOrientation = 90
            else -> {}
        }
        val cameraOrientation = naturalCameraOrientation
        // The device orientation is the device's rotation relative to its
        // natural position.
        val cameraRotation = roundRotation(currentDeviceOrientation)
        var totalCameraRotation = 0
        val usingFrontCamera = isFrontCamera
        totalCameraRotation = if (usingFrontCamera) {
            // The front camera rotates in the opposite direction of the
            // device.
            val inverseCameraRotation = (360 - cameraRotation) % 360
            (inverseCameraRotation + cameraOrientation) % 360
        } else {
            (cameraRotation + cameraOrientation) % 360
        }
        return totalCameraRotation
    }

    /*
     * demonstrate how to use metadata
     */
    interface CustomVideoCapturerDataSource {
        fun retrieveMetadata(): ByteArray
    }

    private var metadataSource: CustomVideoCapturerDataSource? = null


    fun setCustomVideoCapturerDataSource(metadataSource: CustomVideoCapturerDataSource?) {
        this.metadataSource = metadataSource
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        previewBufferLock.lock()
        if (isCaptureRunning) {
            // If StartCapture has been called but not StopCapture
            // Call the C++ layer with the captured frame
            if (data.size == expectedFrameSize) {
                val currentRotation = compensateCameraRotation(
                    currentDisplay
                        .rotation
                )

                // Send buffer
                if (metadataSource != null) {
                    val frameMetadata = metadataSource!!.retrieveMetadata()
                    provideByteArrayFrame(
                        data, NV21, captureWidth, captureHeight, currentRotation,
                        isFrontCamera, frameMetadata
                    )
                } else {
                    provideByteArrayFrame(
                        data, NV21, captureWidth, captureHeight, currentRotation,
                        isFrontCamera
                    )
                }

                // Give the video buffer to the camera service again.
                camera.addCallbackBuffer(data)
            }
        }
        previewBufferLock.unlock()
    }

    fun setPublisher(publisher: Publisher?) {
        this.publisher = publisher
    }

    private fun forceCameraRelease(cameraIndex: Int): Boolean {
        var camera: Camera? = null
        camera = try {
            Camera.open(cameraIndex)
        } catch (e: RuntimeException) {
            return true
        } finally {
            camera?.release()
        }
        return false
    }

    private fun getPreferredResolution(): VideoUtils.Size {
        val resolution = VideoUtils.Size()
        when (preferredResolution) {
            CameraCaptureResolution.LOW -> {
                resolution.width = 352
                resolution.height = 288
            }

            CameraCaptureResolution.MEDIUM -> {
                resolution.width = 640
                resolution.height = 480
            }

            CameraCaptureResolution.HIGH -> {
                resolution.width = 1280
                resolution.height = 720
            }

            else -> {}
        }
        return resolution
    }

    private fun getPreferredFrameRate(): Int {
        var frameRate = 0
        when (preferredFrameRate) {
            CameraCaptureFrameRate.FPS_30 -> frameRate = 30
            CameraCaptureFrameRate.FPS_15 -> frameRate = 15
            CameraCaptureFrameRate.FPS_7 -> frameRate = 7
            CameraCaptureFrameRate.FPS_1 -> frameRate = 1
            else -> {}
        }
        return frameRate
    }

    private fun configureCaptureSize(preferredWidth: Int, preferredHeight: Int) {
        var sizes: List<Camera.Size>? = null
        val preferredFrameRate = getPreferredFrameRate()
        try {
            val parameters = camera!!.parameters
            sizes = parameters.supportedPreviewSizes
            captureFpsRange = findClosestEnclosingFpsRange(
                preferredFrameRate * 1000,
                parameters.supportedPreviewFpsRange
            )
        } catch (exp: RuntimeException) {
            Log.e(TAG, "Error configuring capture size")
        }
        var maxw = 0
        var maxh = 0
        for (i in sizes!!.indices) {
            val size = sizes[i]
            if (size.width >= maxw && size.height >= maxh) {
                if (size.width <= preferredWidth && size.height <= preferredHeight) {
                    maxw = size.width
                    maxh = size.height
                }
            }
        }
        if (maxw == 0 || maxh == 0) {
            // Not found a smaller resolution close to the preferred
            // So choose the lowest resolution possible
            var size = sizes[0]
            var minw = size.width
            var minh = size.height
            for (i in 1 until sizes.size) {
                size = sizes[i]
                if (size.width <= minw && size.height <= minh) {
                    minw = size.width
                    minh = size.height
                }
            }
            maxw = minw
            maxh = minh
        }
        captureWidth = maxw
        captureHeight = maxh
    }

    private fun findClosestEnclosingFpsRange(
        preferredFps: Int,
        supportedFpsRanges: List<IntArray>?
    ): IntArray {
        var preferredFps = preferredFps
        if (supportedFpsRanges == null || supportedFpsRanges.size == 0) {
            return intArrayOf(0, 0)
        }
        /* Because some versions of the Samsung S5 have luminescence issues with 30fps front
           faced cameras, lock to 24 */if (isFrontCamera && "samsung-sm-g900a" == Build.MODEL.lowercase(
                Locale.getDefault()
            ) && 30000 == preferredFps
        ) {
            preferredFps = 24000
        }
        val fps = preferredFps
        val closestRange = Collections.min(supportedFpsRanges, object : Comparator<IntArray?> {
            // Progressive penalty if the upper bound is further away than |MAX_FPS_DIFF_THRESHOLD|
            // from requested.
            private val MAX_FPS_DIFF_THRESHOLD = 5000
            private val MAX_FPS_LOW_DIFF_WEIGHT = 1
            private val MAX_FPS_HIGH_DIFF_WEIGHT = 3
            // Progressive penalty if the lower bound is bigger than |MIN_FPS_THRESHOLD|.
            private val MIN_FPS_THRESHOLD = 8000
            private val MIN_FPS_LOW_VALUE_WEIGHT = 1
            private val MIN_FPS_HIGH_VALUE_WEIGHT = 4

            // Use one weight for small |value| less than |threshold|, and another weight above.
            private fun progressivePenalty(
                value: Int,
                threshold: Int,
                lowWeight: Int,
                highWeight: Int
            ): Int {
                return if (value < threshold) value * lowWeight else threshold * lowWeight + (value - threshold) * highWeight
            }

            private fun diff(range: IntArray): Int {
                val minFpsError = progressivePenalty(
                    range[0],
                    MIN_FPS_THRESHOLD, MIN_FPS_LOW_VALUE_WEIGHT, MIN_FPS_HIGH_VALUE_WEIGHT
                )
                val maxFpsError = progressivePenalty(
                    Math.abs(fps * 1000 - range[1]),
                    MAX_FPS_DIFF_THRESHOLD, MAX_FPS_LOW_DIFF_WEIGHT, MAX_FPS_HIGH_DIFF_WEIGHT
                )
                return minFpsError + maxFpsError
            }

            override fun compare(lhs: IntArray?, rhs: IntArray?): Int {
                if (lhs!=null && rhs!= null){
                    return diff(lhs) - diff(rhs)
                } else return 0
            }
        })
        checkRangeWithWarning(preferredFps, closestRange)
        return closestRange
    }

    private fun checkRangeWithWarning(preferredFps: Int, range: IntArray) {
        if (preferredFps < range[0] || preferredFps > range[1]) {
            Log.w(
                TAG,
                "Closest fps range found: " + range[0] / 1000 + range[1] / 1000 + "for desired fps: " + preferredFps / 1000
            )
        }
    }

    companion object {
        private val TAG = MirrorVideoCapturer::class.java.simpleName
        private const val PIXEL_FORMAT = ImageFormat.NV21
        private fun roundRotation(rotation: Int): Int {
            return (Math.round(rotation.toDouble() / 90) * 90).toInt() % 360
        }

        private fun getCameraIndexUsingFront(front: Boolean): Int {
            for (i in 0 until Camera.getNumberOfCameras()) {
                val info = CameraInfo()
                Camera.getCameraInfo(i, info)
                if (front && info.facing == CameraInfo.CAMERA_FACING_FRONT) {
                    return i
                } else if (!front
                    && info.facing == CameraInfo.CAMERA_FACING_BACK
                ) {
                    return i
                }
            }
            return 0
        }
    }
}