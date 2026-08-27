package dev.camstream.app

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Drives CameraX in [ImageAnalysis] mode and converts each YUV_420_888 frame straight to a
 * JPEG byte array, publishing it to [frameHolder]. This is the "jpg" codec path from
 * PROTOCOL.md, so no MediaCodec/H.264 setup is needed for the MVP.
 */
class CameraCapture(
    private val context: Context,
    private val frameHolder: LatestFrameHolder,
    private val jpegQuality: Int = 70,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var analysis: ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile var frameWidth: Int = 0
        private set

    @Volatile var frameHeight: Int = 0
        private set

    @Volatile var isFrontCamera: Boolean = false
        private set

    fun start(lifecycleOwner: LifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            // Pin the same target resolution regardless of which physical camera is bound: the
            // PC decoder sizes its buffers once from the 9-byte header sent at connection time
            // and never re-reads it (see decoder.c decoder_prepare_video()), so a front/back
            // switch that changed frame size mid-connection would corrupt or crash the decode.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy -> processFrame(imageProxy) }
            this.analysis = analysis

            bind(isFrontCamera)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        lifecycleOwner = null
        analysis = null
        frameWidth = 0
        frameHeight = 0
    }

    /**
     * Switches between the back and front camera, rebinding the same [analysis] use case.
     * No-op (logged, not thrown) if the requested camera isn't available on this device, or
     * if capture hasn't started yet.
     */
    fun switchCamera() {
        val provider = cameraProvider ?: return
        val wantFront = !isFrontCamera
        val selector = if (wantFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) return
        frameWidth = 0
        frameHeight = 0
        bind(wantFront)
    }

    private fun bind(front: Boolean) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val useCase = analysis ?: return
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        provider.bindToLifecycle(owner, selector, useCase)
        isFrontCamera = front
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            if (imageProxy.format != ImageFormat.YUV_420_888) return
            val jpeg = yuv420ToJpeg(imageProxy, jpegQuality)
            frameWidth = imageProxy.width
            frameHeight = imageProxy.height
            frameHolder.publish(jpeg)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuv420ToJpeg(image: ImageProxy, quality: Int): ByteArray {
        val nv21 = imageProxyToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    /** Packs a CameraX YUV_420_888 [ImageProxy] into a contiguous NV21 byte array. */
    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (yPixelStride == 1) {
                yBuffer.position(rowStart)
                yBuffer.get(nv21, pos, width)
                pos += width
            } else {
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(rowStart + col * yPixelStride)
                }
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val rowStart = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val index = rowStart + col * uvPixelStride
                nv21[pos++] = vBuffer.get(index)
                nv21[pos++] = uBuffer.get(index)
            }
        }

        return nv21
    }
}
