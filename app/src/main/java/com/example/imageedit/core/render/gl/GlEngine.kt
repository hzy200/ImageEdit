package com.example.imageedit.core.render.gl

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.imageedit.core.render.PreviewRenderState
import com.example.imageedit.core.render.PreviewRenderer

/**
 * Owns the preview EGL context and serializes all GL work on one thread.
 * Rendering is on demand: state updates coalesce into a single pending draw.
 */
class GlEngine(
    private val onFailure: (String) -> Unit,
) {
    private val thread = HandlerThread("GlPreview")
    private val handler: Handler
    private val renderTask = Runnable { draw() }

    private var eglCore: EglCore? = null
    private var windowSurface: EGLSurface? = null
    private var renderer: PreviewRenderer? = null
    private var surface: Surface? = null
    private var pendingState: PreviewRenderState? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var failed = false
    private var released = false

    private var frameCount = 0
    private var lastSwapNanos = 0L
    private var swapIntervalNanos = 0L
    private var drawNanos = 0L

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    fun onSurfaceCreated(surface: Surface) {
        handler.post {
            if (failed || released) return@post
            try {
                this.surface = surface
                ensureContext()
                windowSurface?.let { eglCore?.releaseSurface(it) }
                windowSurface = eglCore?.createWindowSurface(surface)
                check(windowSurface != null) { "window surface unavailable" }
                makeCurrent()
                renderer?.release()
                renderer = PreviewRenderer().also { it.onSurfaceCreated() }
                Log.i(TAG, "surface created")
                draw()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        handler.post {
            surfaceWidth = width
            surfaceHeight = height
            draw()
        }
    }

    fun onSurfaceDestroyed() {
        handler.post {
            renderer?.release()
            renderer = null
            windowSurface?.let { eglCore?.releaseSurface(it) }
            windowSurface = null
            eglCore?.makeNothingCurrent()
            surface = null
        }
    }

    fun updateState(state: PreviewRenderState) {
        handler.post {
            pendingState = state
            handler.removeCallbacks(renderTask)
            handler.post(renderTask)
        }
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            renderer?.release()
            renderer = null
            windowSurface?.let { eglCore?.releaseSurface(it) }
            windowSurface = null
            eglCore?.release()
            eglCore = null
            surface = null
            thread.quitSafely()
        }
    }

    private fun ensureContext() {
        if (eglCore == null) {
            eglCore = EglCore()
            Log.i(TAG, "EGL context ready")
        }
    }

    private fun makeCurrent() {
        val core = eglCore ?: error("no EGL context")
        val window = windowSurface ?: error("no EGL surface")
        check(core.makeCurrent(window)) { "eglMakeCurrent failed" }
    }

    private fun draw() {
        if (failed || released) return
        val core = eglCore ?: return
        val window = windowSurface ?: return
        val current = renderer ?: return
        val state = pendingState ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        try {
            if (!core.makeCurrent(window)) error("eglMakeCurrent failed")
            val start = System.nanoTime()
            current.render(state, surfaceWidth, surfaceHeight)
            drawNanos += System.nanoTime() - start
            if (!core.swapBuffers(window)) {
                val error = EGL14.eglGetError()
                if (error == EGL_CONTEXT_LOST) {
                    recoverContext()
                } else {
                    Log.w(TAG, "eglSwapBuffers failed 0x${Integer.toHexString(error)}")
                }
                return
            }
            logFrameRate()
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun recoverContext() {
        Log.w(TAG, "EGL context lost; recreating")
        renderer?.release()
        renderer = null
        windowSurface?.let { eglCore?.releaseSurface(it) }
        windowSurface = null
        eglCore?.release()
        eglCore = null
        val currentSurface = surface ?: return
        try {
            ensureContext()
            windowSurface = eglCore?.createWindowSurface(currentSurface)
            check(windowSurface != null) { "window surface unavailable" }
            makeCurrent()
            renderer = PreviewRenderer().also { it.onSurfaceCreated() }
            draw()
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun logFrameRate() {
        val now = System.nanoTime()
        val gap = now - lastSwapNanos
        if (lastSwapNanos == 0L || gap > BURST_GAP_NANOS) {
            // Idle gaps between edits are not frame intervals.
            lastSwapNanos = now
            swapIntervalNanos = 0L
            drawNanos = 0L
            frameCount = 0
            return
        }
        swapIntervalNanos += gap
        lastSwapNanos = now
        frameCount++
        if (frameCount >= 30) {
            val avgFrameMs = swapIntervalNanos / frameCount / 1_000_000.0
            val avgDrawMs = drawNanos / frameCount / 1_000_000.0
            val fps = if (avgFrameMs > 0.0) 1000.0 / avgFrameMs else 0.0
            Log.i(
                TAG,
                "fps=${"%.1f".format(fps)} frameMs=${"%.2f".format(avgFrameMs)} " +
                    "drawMs=${"%.2f".format(avgDrawMs)}",
            )
            frameCount = 0
            swapIntervalNanos = 0L
            drawNanos = 0L
        }
    }

    private fun fail(error: Throwable) {
        if (failed) return
        failed = true
        Log.e(TAG, "GL preview failed; falling back to CPU", error)
        onFailure(error.message ?: "GL preview failed")
    }

    private companion object {
        const val TAG = "GlPreview"
        const val EGL_CONTEXT_LOST = 0x300E
        const val BURST_GAP_NANOS = 500_000_000L
    }
}
