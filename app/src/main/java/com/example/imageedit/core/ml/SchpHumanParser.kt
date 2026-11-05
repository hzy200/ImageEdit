package com.example.imageedit.core.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * SCHP-ATR human parsing (INT8 ONNX). Output is one class index per pixel.
 * Only garment masks are exposed; no image pixels are generated.
 */
object SchpHumanParser {
    private const val TAG = "SchpHumanParser"
    const val INPUT_SIZE = 512

    val labels = listOf(
        "background", "hat", "hair", "sunglasses", "upper_clothes", "skirt",
        "pants", "dress", "belt", "left_shoe", "right_shoe", "face",
        "left_leg", "right_leg", "left_arm", "right_arm", "bag", "scarf",
    )

    private val classIndex = mapOf(
        "upper_clothes" to 4,
        "skirt" to 5,
        "pants" to 6,
        "dress" to 7,
        "scarf" to 17,
    )

    private val mean = floatArrayOf(0.406f, 0.456f, 0.485f)
    private val std = floatArrayOf(0.225f, 0.224f, 0.229f)

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var environment: OrtEnvironment? = null

    @Volatile
    private var inputName: String = "input"

    @Synchronized
    fun parseGarment(
        source: Bitmap,
        classes: List<String>,
    ): Bitmap {
        val selected = classes.mapNotNull { classIndex[it.trim().lowercase()] }.toSet()
        require(selected.isNotEmpty()) { "未识别的服装类别: $classes" }

        val logits = infer(source)
        val plane = INPUT_SIZE * INPUT_SIZE
        check(logits.size == labels.size * plane) {
            "SCHP 输出长度不匹配：期望 ${labels.size * plane}，实际 ${logits.size}"
        }
        val maskPixels = IntArray(plane)
        var hit = 0
        for (pixel in 0 until plane) {
            var bestClass = 0
            var bestScore = logits[pixel]
            for (classId in 1 until labels.size) {
                val score = logits[classId * plane + pixel]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }
            if (bestClass in selected) {
                maskPixels[pixel] = Color.WHITE
                hit++
            } else {
                maskPixels[pixel] = Color.BLACK
            }
        }
        Log.i(TAG, "selected=$selected hit=$hit/${plane}")

        val mask = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        mask.setPixels(maskPixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (source.width == INPUT_SIZE && source.height == INPUT_SIZE) return mask
        return Bitmap.createScaledBitmap(mask, source.width, source.height, false).also {
            mask.recycle()
        }
    }

    private fun infer(source: Bitmap): FloatArray {
        val currentSession = ensureSession()
        val env = environment ?: error("ONNX Runtime 未初始化")

        val scaled = if (source.width == INPUT_SIZE && source.height == INPUT_SIZE) {
            source
        } else {
            Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== source) scaled.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val data = FloatArray(plane * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            data[index] = (r - mean[0]) / std[0]
            data[plane + index] = (g - mean[1]) / std[1]
            data[plane * 2 + index] = (b - mean[2]) / std[2]
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        try {
            currentSession.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return values
            }
        } finally {
            tensor.close()
        }
    }

    @Synchronized
    private fun ensureSession(): OrtSession {
        session?.let { return it }
        Log.i(TAG, "loading ${Models.schpAtr.assetPath}")
        val env = OrtEnvironment.getEnvironment()
        val xnnpackOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(1)
            runCatching {
                addXnnpack(mapOf("intra_op_num_threads" to "2"))
            }.onFailure {
                Log.w(TAG, "XNNPACK unavailable", it)
            }
        }
        val handle = ModelManager.open(Models.schpAtr)
        val modelBytes = handle.opener().use { it.readBytes() }

        var backend = "XNNPACK"
        val created = runCatching {
            env.createSession(modelBytes, xnnpackOptions)
        }.getOrElse { xnnpackError ->
            backend = "CPU"
            Log.w(TAG, "XNNPACK session failed, falling back to CPU EP", xnnpackError)
            runCatching { xnnpackOptions.close() }
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
