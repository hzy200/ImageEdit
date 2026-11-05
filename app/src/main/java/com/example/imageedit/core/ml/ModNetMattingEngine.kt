package com.example.imageedit.core.ml

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * MODNet portrait matting via ONNX Runtime Mobile.
 *
 * Preprocess: RGB, [-1, 1], NCHW. Output: single-channel alpha in [0, 1].
 */
object ModNetMattingEngine {
    private const val TAG = "ModNetMatting"
    private const val DEFAULT_SIZE = 512
    private const val MIN_SIZE = 256
    private const val MAX_SIZE = 768

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var environment: OrtEnvironment? = null

    @Volatile
    private var inputName: String = "input"

    @Synchronized
    fun cutout(
        source: Bitmap,
        featherPx: Float = 1.5f,
        inputSize: Int = DEFAULT_SIZE,
    ): Bitmap {
        val size = inputSize.coerceIn(MIN_SIZE, MAX_SIZE)
        val alpha = inferAlpha(source, size)
        val alphaBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (index in alpha.indices) {
            val value = (alpha[index].coerceIn(0f, 1f) * 255f).toInt()
            pixels[index] = Color.argb(value, 0, 0, 0)
        }
        alphaBitmap.setPixels(pixels, 0, size, 0, 0, size, size)

        val scaledAlpha = if (source.width == size && source.height == size) {
            alphaBitmap
        } else {
            Bitmap.createScaledBitmap(alphaBitmap, source.width, source.height, true).also {
                alphaBitmap.recycle()
            }
        }

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        output.setHasAlpha(true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            if (featherPx > 0f) {
                maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawBitmap(scaledAlpha, 0f, 0f, paint)
        scaledAlpha.recycle()
        return output
    }

    fun alphaMask(source: Bitmap, featherPx: Float = 0f): Bitmap {
        val cutout = cutout(source, featherPx)
        val mask = cutout.extractAlpha()
        cutout.recycle()
        return mask
    }

    private fun inferAlpha(source: Bitmap, size: Int): FloatArray {
        val currentSession = ensureSession()
        val env = environment ?: error("ONNX Runtime 未初始化")

        val scaled = if (source.width == size && source.height == size) {
            source
        } else {
            Bitmap.createScaledBitmap(source, size, size, true)
        }
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== source) scaled.recycle()

        val plane = size * size
        val data = FloatArray(plane * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            data[index] = (r - 0.5f) / 0.5f
            data[plane + index] = (g - 0.5f) / 0.5f
            data[plane * 2 + index] = (b - 0.5f) / 0.5f
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, size.toLong(), size.toLong()),
        )
        try {
            currentSession.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val buffer = output.floatBuffer
                val alpha = FloatArray(plane)
                buffer.get(alpha)
                return alpha
            }
        } finally {
            tensor.close()
        }
    }

    @Synchronized
    private fun ensureSession(): OrtSession {
        session?.let { return it }
        Log.i(TAG, "loading ${Models.modnet.assetPath}")
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // XNNPACK owns its own thread pool; keep ORT's pool at 1 to avoid contention.
            setIntraOpNumThreads(1)
            runCatching {
                addXnnpack(mapOf("intra_op_num_threads" to "2"))
            }.onFailure {
                Log.w(TAG, "XNNPACK unavailable, falling back to CPU", it)
            }
        }
        val handle = ModelManager.open(Models.modnet)
        val modelBytes = handle.opener().use { it.readBytes() }
        var backend = "XNNPACK"
        val created = runCatching {
            env.createSession(modelBytes, options)
        }.getOrElse { xnnpackError ->
            backend = "CPU"
            Log.w(TAG, "XNNPACK session failed, falling back to CPU", xnnpackError)
            runCatching { options.close() }
            val cpuOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
            }
            env.createSession(modelBytes, cpuOptions)
        }
        environment = env
        inputName = created.inputNames.firstOrNull() ?: "input"
        session = created
        Log.i(TAG, "loaded ${modelBytes.size / 1024 / 1024} MB, input=$inputName, backend=$backend")
        return created
    }
}
