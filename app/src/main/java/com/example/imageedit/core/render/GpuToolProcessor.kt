package com.example.imageedit.core.render

import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import com.example.imageedit.core.assets.AssetLibrary
import com.example.imageedit.core.image.ColorAdjustMatrix
import com.example.imageedit.core.image.GeometryTransforms
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.image.TextureOps
import com.example.imageedit.core.render.gl.BackgroundPass
import com.example.imageedit.core.render.gl.BlurPass
import com.example.imageedit.core.render.gl.ColorAdjustPass
import com.example.imageedit.core.render.gl.DisplayPass
import com.example.imageedit.core.render.gl.EglCore
import com.example.imageedit.core.render.gl.GarmentHuePass
import com.example.imageedit.core.render.gl.GlFbo
import com.example.imageedit.core.render.gl.GlTexture
import com.example.imageedit.core.render.gl.MaskBlendPass
import com.example.imageedit.core.render.gl.TextureWarpPass
import com.example.imageedit.core.render.gl.TransformPass
import com.example.imageedit.core.tools.ToolInvocation
import com.example.imageedit.core.tools.ToolRegistry
import com.example.imageedit.core.tools.ValidationResult
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offscreen GLES 3.0 executor for the tools that pass [GpuToolPolicy].
 *
 * The executor owns a private EGL context and is serialized. All Bitmap inputs
 * are uploaded directly and results are read back through a PBO. Any failure
 * returns null so the caller can run the CPU implementation.
 */
object GpuToolProcessor : ImageProcessor {
    private const val TAG = "GpuToolProcessor"

    private var eglCore: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var readBuffer: ByteBuffer? = null
    private var maxTextureSize = 4096
    private var unavailable = false

    private var displayPass: DisplayPass? = null
    private var colorAdjustPass: ColorAdjustPass? = null
    private var maskBlendPass: MaskBlendPass? = null
    private var transformPass: TransformPass? = null
    private var garmentHuePass: GarmentHuePass? = null
    private var textureWarpPass: TextureWarpPass? = null
    private var backgroundPass: BackgroundPass? = null
    private var blurPass: BlurPass? = null

    private val inputTexture = GlTexture()
    private val maskTexture = GlTexture()
    private val patternTexture = GlTexture()
    private val backgroundTexture = GlTexture()

    private var resultFbo: GlFbo? = null
    private var readbackFbo: GlFbo? = null

    private var patternCacheKey: String? = null
    private var patternCacheBitmap: Bitmap? = null

    override fun execute(
        context: ImageContext,
        invocation: ToolInvocation,
        checkActive: () -> Unit,
    ): ImageContext? {
        val validated = when (val result = ToolRegistry.validate(invocation.tool, invocation.args)) {
            is ValidationResult.Fail -> return null
            is ValidationResult.Ok -> result.args
        }
        return executeValidated(context, invocation.tool, validated, checkActive)
    }

    @Synchronized
    internal fun executeValidated(
        context: ImageContext,
        tool: String,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext? {
        if (unavailable) return null
        if (!GpuToolPolicy.eligible(tool, args, context.width, context.height)) return null
        if (!ensureInitialized()) return null
        val core = eglCore ?: return null
        val surface = pbuffer ?: return null
        if (!core.makeCurrent(surface)) {
            Log.w(TAG, "eglMakeCurrent failed; falling back to CPU")
            return null
        }
        drainGlErrors()
        val start = System.nanoTime()
        return try {
            checkActive()
            val result = when (tool) {
                "adjust_color" -> adjustColor(context, args, checkActive)
                "crop", "rotate", "flip" -> geometry(context, tool, args)
                "recolor_hsv" -> recolor(context, args)
                "apply_texture" -> texture(context, args)
                "replace_background" -> background(context, args, checkActive)
                else -> null
            }
            checkActive()
            val usable = result != null && checkGlError(tool)
            if (!usable && result != null) {
                if (result.bitmap !== context.bitmap && !result.bitmap.isRecycled) {
                    result.bitmap.recycle()
                }
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            if (usable) {
                Log.i(TAG, "$tool=${"%.1f".format(elapsed)}ms")
                result
            } else {
                Log.w(TAG, "$tool failed; CPU fallback after ${"%.1f".format(elapsed)}ms")
                null
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(TAG, "GPU tool $tool failed; falling back to CPU", error)
            null
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun ensureInitialized(): Boolean {
        if (unavailable) return false
        if (eglCore != null) return true
        return try {
            val core = EglCore()
            val surface = core.createPBufferSurface(1, 1)
            check(core.makeCurrent(surface)) { "eglMakeCurrent failed" }
            eglCore = core
            pbuffer = surface
            val max = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
            maxTextureSize = if (max[0] > 0) max[0] else 4096
            displayPass = DisplayPass()
            colorAdjustPass = ColorAdjustPass()
            maskBlendPass = MaskBlendPass()
            transformPass = TransformPass()
            garmentHuePass = GarmentHuePass()
            textureWarpPass = TextureWarpPass()
            backgroundPass = BackgroundPass()
            blurPass = BlurPass()
            Log.i(TAG, "offscreen GL ready, maxTexture=$maxTextureSize")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "offscreen GL init failed; GPU tools disabled", error)
            unavailable = true
            releaseInternal()
            false
        }
    }

    private fun adjustColor(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext? {
        if (!uploadInput(context)) return null
        val brightness = (args["brightness"] as? Number)?.toFloat() ?: 0f
        val contrast = (args["contrast"] as? Number)?.toFloat() ?: 0f
        val saturation = (args["saturation"] as? Number)?.toFloat() ?: 1f
        val matrix = GlColorMatrix.from(
            ColorAdjustMatrix.build(brightness, contrast, saturation),
        )
        val applyTo = args["apply_to"]?.toString() ?: "whole"
        val output = if (applyTo == "selection") {
            val mask = context.masks["selection"] ?: return null
            if (!maskTexture.uploadBitmap(mask)) return null
            val fbos = ensureFbos(context.width, context.height) ?: return null
            val (result, readback) = fbos
            readback.clear()
            readback.bind()
            colorAdjustPass?.draw(
                textureId = inputTexture.id,
                matrix = matrix.matrix,
                offset = matrix.offset,
                blend = false,
            ) ?: return null
            checkActive()
            result.clear()
            result.bind()
            maskBlendPass?.draw(
                adjustedTextureId = readback.textureId,
                originalTextureId = inputTexture.id,
                maskTextureId = maskTexture.id,
            ) ?: return null
            copyForReadback(result, readback)?.let { readPixels(it) }
        } else {
            renderToBitmap(context.width, context.height) {
                colorAdjustPass?.draw(
                    textureId = inputTexture.id,
                    matrix = matrix.matrix,
                    offset = matrix.offset,
                    blend = false,
                ) ?: return@renderToBitmap
            }
        } ?: return null
        return context.copy(bitmap = output)
    }

    private fun geometry(
        context: ImageContext,
        tool: String,
        args: Map<String, Any>,
    ): ImageContext? {
        if (!uploadInput(context)) return null
        val width = context.width
        val height = context.height
        val outWidth: Int
        val outHeight: Int
        val uvX: FloatArray
        val uvY: FloatArray
        when (tool) {
            "crop" -> {
                val rect = GeometryTransforms.cropRect(width, height, args)
                outWidth = rect.width
                outHeight = rect.height
                uvX = floatArrayOf(
                    rect.width.toFloat() / width,
                    0f,
                    rect.x.toFloat() / width,
                )
                uvY = floatArrayOf(
                    0f,
                    rect.height.toFloat() / height,
                    rect.y.toFloat() / height,
                )
            }

            "rotate" -> {
                when ((args["degrees"] as? String)?.toIntOrNull() ?: 90) {
                    180 -> {
                        outWidth = width
                        outHeight = height
                        uvX = floatArrayOf(-1f, 0f, 1f)
                        uvY = floatArrayOf(0f, -1f, 1f)
                    }

                    270 -> {
                        outWidth = height
                        outHeight = width
                        uvX = floatArrayOf(0f, -1f, 1f)
                        uvY = floatArrayOf(1f, 0f, 0f)
                    }

                    else -> {
                        outWidth = height
                        outHeight = width
                        uvX = floatArrayOf(0f, 1f, 0f)
                        uvY = floatArrayOf(-1f, 0f, 1f)
                    }
                }
            }

            else -> {
                outWidth = width
                outHeight = height
                if (args["direction"]?.toString() == "vertical") {
                    uvX = floatArrayOf(1f, 0f, 0f)
                    uvY = floatArrayOf(0f, -1f, 1f)
                } else {
                    uvX = floatArrayOf(-1f, 0f, 1f)
                    uvY = floatArrayOf(0f, 1f, 0f)
                }
            }
        }

        val bitmap = renderToBitmap(outWidth, outHeight) {
            transformPass?.draw(inputTexture.id, uvX, uvY, blend = false)
        } ?: return null
        return context.copy(
            bitmap = bitmap,
            masks = GeometryTransforms.transformMasks(context, bitmap, tool, args),
            regionRequest = GeometryTransforms.transformRegion(context.regionRequest, tool, args),
        )
    }

    private fun recolor(context: ImageContext, args: Map<String, Any>): ImageContext? {
        val mask = context.masks["garment"] ?: return null
        if (!uploadInput(context) || !maskTexture.uploadBitmap(mask)) return null
        val hue = (args["hue"] as? Number)?.toFloat() ?: 0f
        val saturation = (args["sat_scale"] as? Number)?.toFloat() ?: 1f
        val output = renderToBitmap(context.width, context.height) {
            garmentHuePass?.draw(
                textureId = inputTexture.id,
                maskTextureId = maskTexture.id,
                hueDegrees = hue,
                saturationScale = saturation,
            )
        } ?: return null
        return context.copy(bitmap = output)
    }

    private fun texture(context: ImageContext, args: Map<String, Any>): ImageContext? {
        val mask = context.masks["garment"] ?: return null
        val textureId = args["texture"]?.toString() ?: return null
        if (textureId == "user_local" || textureId !in AssetLibrary.textureIds) return null
        if (!uploadInput(context) || !maskTexture.uploadBitmap(mask)) return null
        val pattern = preparePattern(textureId, context.width, context.height) ?: return null
        if (!patternTexture.uploadBitmap(pattern)) return null
        val strength = (args["strength"] as? Number)?.toFloat() ?: 30f
        val output = renderToBitmap(context.width, context.height) {
            textureWarpPass?.draw(
                textureId = inputTexture.id,
                maskTextureId = maskTexture.id,
                patternTextureId = patternTexture.id,
                strength = strength,
                width = context.width,
                height = context.height,
            )
        } ?: return null
        return context.copy(bitmap = output)
    }

    private fun background(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext? {
        if (!uploadInput(context)) return null
        val source = args["source"]?.toString() ?: "solid"
        val width = context.width
        val height = context.height
        val backgroundBitmap = when {
            source == "solid" -> null
            source == "gradient" -> AssetLibrary.background("preset:gradient_01", width, height)
            source.startsWith("preset:") -> AssetLibrary.background(source, width, height)
            else -> return null
        }
        checkActive()
        var backgroundTextureId = 0
        var backgroundWidth = width
        var backgroundHeight = height
        if (backgroundBitmap != null) {
            if (!backgroundTexture.uploadBitmap(backgroundBitmap)) return null
            backgroundTextureId = backgroundTexture.id
            backgroundWidth = backgroundBitmap.width
            backgroundHeight = backgroundBitmap.height
        }
        val bokeh = (args["bokeh"] as? Number)?.toFloat() ?: 0f
        val fit = args["fit"]?.toString() ?: "cover"
        val contactShadow = args["contact_shadow"] as? Boolean ?: true
        val color = runCatching {
            android.graphics.Color.parseColor(args["color"]?.toString() ?: "#FFFFFF")
        }.getOrNull() ?: return null

        val output = renderToBitmap(width, height) { result ->
            var textureId = backgroundTextureId
            if (backgroundBitmap != null && bokeh > 1f) {
                textureId = blurPass?.blur(
                    sourceTextureId = backgroundTexture.id,
                    sourceWidth = backgroundWidth,
                    sourceHeight = backgroundHeight,
                    radiusPx = bokeh,
                ) ?: backgroundTextureId
                result.bind()
            }
            backgroundPass?.draw(
                foregroundTextureId = inputTexture.id,
                backgroundTextureId = textureId,
                useSolid = backgroundBitmap == null,
                color = color,
                backgroundWidth = backgroundWidth,
                backgroundHeight = backgroundHeight,
                foregroundWidth = width,
                foregroundHeight = height,
                fit = fit,
                contactShadow = contactShadow,
            )
        } ?: return null
        return context.copy(bitmap = output)
    }

    private fun preparePattern(id: String, width: Int, height: Int): Bitmap? {
        val key = "$id:${width}x$height"
        val cached = patternCacheBitmap
        if (patternCacheKey == key && cached != null && !cached.isRecycled) return cached
        val raw = AssetLibrary.texture(id, width, height)
        val prepared = TextureOps.prepare(raw)
        if (prepared !== raw && !raw.isRecycled) raw.recycle()
        patternCacheBitmap?.let { if (it !== prepared && !it.isRecycled) it.recycle() }
        patternCacheKey = key
        patternCacheBitmap = prepared
        return prepared
    }

    private fun uploadInput(context: ImageContext): Boolean {
        if (context.width > maxTextureSize || context.height > maxTextureSize) return false
        return inputTexture.uploadBitmap(context.bitmap)
    }

    private fun ensureFbos(width: Int, height: Int): Pair<GlFbo, GlFbo>? {
        if (width <= 0 || height <= 0) return null
        if (width > maxTextureSize || height > maxTextureSize) return null
        var result = resultFbo
        if (result == null || result.width != width || result.height != height) {
            result?.release()
            result = GlFbo(width, height)
            resultFbo = result
        }
        var readback = readbackFbo
        if (readback == null || readback.width != width || readback.height != height) {
            readback?.release()
            readback = GlFbo(width, height)
            readbackFbo = readback
        }
        return result to readback
    }

    private fun renderToBitmap(
        width: Int,
        height: Int,
        draw: (GlFbo) -> Unit,
    ): Bitmap? {
        val (result, readback) = ensureFbos(width, height) ?: return null
        result.clear()
        result.bind()
        draw(result)
        copyForReadback(result, readback) ?: return null
        return readPixels(readback)
    }

    /**
     * FBO textures are stored bottom-up for readback. Drawing them through the
     * standard display shader into a second FBO flips them back to top-down.
     */
    private fun copyForReadback(result: GlFbo, readback: GlFbo): GlFbo? {
        readback.clear()
        readback.bind()
        displayPass?.draw(result.textureId) ?: return null
        return readback
    }

    private fun readPixels(fbo: GlFbo): Bitmap? {
        val bytes = fbo.width * fbo.height * 4
        var buffer = readBuffer
        if (buffer == null || buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            readBuffer = buffer
        }
        buffer.clear()
        buffer.limit(bytes)
        GLES30.glReadPixels(
            0,
            0,
            fbo.width,
            fbo.height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer,
        )
        buffer.position(0)
        buffer.limit(bytes)
        val bitmap = Bitmap.createBitmap(fbo.width, fbo.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        bitmap.setHasAlpha(true)
        return bitmap
    }

    private fun checkGlError(tool: String): Boolean {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "GL error 0x${Integer.toHexString(error)} after $tool")
            return false
        }
        return true
    }

    private fun drainGlErrors() {
        var guard = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && guard < 16) {
            guard++
        }
    }

    private fun releaseInternal() {
        readBuffer = null
        inputTexture.release()
        maskTexture.release()
        patternTexture.release()
        backgroundTexture.release()
        resultFbo?.release()
        readbackFbo?.release()
        resultFbo = null
        readbackFbo = null
        blurPass?.release()
        displayPass?.release()
        colorAdjustPass?.release()
        maskBlendPass?.release()
        transformPass?.release()
        garmentHuePass?.release()
        textureWarpPass?.release()
        backgroundPass?.release()
        blurPass = null
        displayPass = null
        colorAdjustPass = null
        maskBlendPass = null
        transformPass = null
        garmentHuePass = null
        textureWarpPass = null
        backgroundPass = null
        patternCacheBitmap?.let { if (!it.isRecycled) it.recycle() }
        patternCacheBitmap = null
        patternCacheKey = null
        pbuffer?.let { runCatching { eglCore?.releaseSurface(it) } }
        pbuffer = null
        eglCore?.let { runCatching { it.release() } }
        eglCore = null
    }
}
