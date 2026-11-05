package com.example.imageedit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.imageedit.core.render.PreviewDraft
import com.example.imageedit.core.render.PreviewFrame
import com.example.imageedit.core.render.PreviewRenderDraft
import com.example.imageedit.core.render.PreviewRenderState
import com.example.imageedit.core.render.PreviewScaleMode
import com.example.imageedit.core.render.PreviewViewport
import com.example.imageedit.core.render.gl.GlEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SurfaceView host for the GL preview. Bitmap snapshots are copied on a
 * background thread, so the main thread never blocks on 2048px pixel copies.
 */
class GlPreviewView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    var onGlFailure: (() -> Unit)? = null
    var onViewportChanged: ((PreviewViewport) -> Unit)? = null
    var gesturesEnabled: Boolean = true

    private var engine: GlEngine? = null
    private var pendingState: PreviewRenderState? = null
    private var snapshotExecutor: ExecutorService? = null

    private class SnapshotSlot {
        var bitmap: Bitmap? = null
        var frame: PreviewFrame? = null
        var token: Long = 0L
    }

    private val resultSlot = SnapshotSlot()
    private val originalSlot = SnapshotSlot()
    private val maskSlot = SnapshotSlot()
    private val patternSlot = SnapshotSlot()
    private val backgroundSlot = SnapshotSlot()

    private var draft: PreviewDraft? = null
    private var compareMode = false
    private var compareFraction = 0.5f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var reportedZoom = 1f
    private var reportedPanX = 0f
    private var reportedPanY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
                if (next != zoom) {
                    zoom = next
                    if (zoom <= 1.001f) {
                        panX = 0f
                        panY = 0f
                    }
                    pushState()
                }
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (zoom <= 1.001f) return false
                panX -= distanceX
                panY -= distanceY
                pushState()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoom = if (zoom > 1.01f) 1f else 2f
                if (zoom <= 1.001f) {
                    panX = 0f
                    panY = 0f
                }
                pushState()
                return true
            }
        },
    )

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.OPAQUE)
    }

    fun update(
        result: Bitmap?,
        original: Bitmap?,
        compareMode: Boolean,
        compareFraction: Float,
        draft: PreviewDraft?,
    ) {
        this.compareMode = compareMode
        this.compareFraction = compareFraction
        this.draft = draft
        ensureSnapshotter()

        if (result !== resultSlot.bitmap) {
            requestFrame(resultSlot, result)
        }
        if (result != null && original === result) {
            if (originalSlot.bitmap !== result) {
                originalSlot.bitmap = result
                originalSlot.token++
                originalSlot.frame = resultSlot.frame
            }
        } else if (original !== originalSlot.bitmap) {
            requestFrame(originalSlot, original)
        }

        val mask = when (draft) {
            is PreviewDraft.Color -> draft.mask
            is PreviewDraft.GarmentHue -> draft.mask
            is PreviewDraft.Texture -> draft.mask
            else -> null
        }
        if (mask !== maskSlot.bitmap) requestFrame(maskSlot, mask)

        val pattern = (draft as? PreviewDraft.Texture)?.texture
        if (pattern !== patternSlot.bitmap) requestFrame(patternSlot, pattern)

        val background = (draft as? PreviewDraft.Background)?.bitmap
        if (background !== backgroundSlot.bitmap) requestFrame(backgroundSlot, background)

        pushState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureEngine()
        ensureSnapshotter()
        pendingState?.let { engine?.updateState(it) }
        forceReportViewport()
    }

    override fun onDetachedFromWindow() {
        engine?.release()
        engine = null
        snapshotExecutor?.shutdownNow()
        snapshotExecutor = null
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ensureEngine().onSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine?.onSurfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.onSurfaceDestroyed()
    }

    private fun pushState() {
        val renderDraft = when (val current = draft) {
            null -> null

            is PreviewDraft.Color -> {
                if (current.mask == null) {
                    PreviewRenderDraft.Color(current.matrix, current.offset)
                } else {
                    maskSlot.frame?.let {
                        PreviewRenderDraft.Color(current.matrix, current.offset, it)
                    }
                }
            }

            is PreviewDraft.GarmentHue -> maskSlot.frame?.let {
                PreviewRenderDraft.GarmentHue(it, current.hue, current.satScale, current.preserveV)
            }

            is PreviewDraft.Texture -> {
                val mask = maskSlot.frame
                val pattern = patternSlot.frame
                if (mask != null && pattern != null) {
                    PreviewRenderDraft.Texture(mask, pattern, current.strength)
                } else {
                    null
                }
            }

            is PreviewDraft.Background -> {
                if (current.bitmap == null) {
                    PreviewRenderDraft.Background(
                        current.color,
                        null,
                        current.fit,
                        current.bokeh,
                        current.contactShadow,
                    )
                } else {
                    backgroundSlot.frame?.let {
                        PreviewRenderDraft.Background(
                            current.color,
                            it,
                            current.fit,
                            current.bokeh,
                            current.contactShadow,
                        )
                    }
                }
            }
        }
        val state = PreviewRenderState(
            result = resultSlot.frame,
            original = originalSlot.frame,
            compareMode = compareMode,
            compareFraction = compareFraction,
            draft = renderDraft,
            viewport = PreviewViewport(zoom, panX, panY, PreviewScaleMode.FIT),
        )
        pendingState = state
        engine?.updateState(state)
        reportViewport()
    }

    private fun reportViewport() {
        if (zoom == reportedZoom && panX == reportedPanX && panY == reportedPanY) return
        reportedZoom = zoom
        reportedPanX = panX
        reportedPanY = panY
        val viewport = PreviewViewport(zoom, panX, panY, PreviewScaleMode.FIT)
        post { onViewportChanged?.invoke(viewport) }
    }

    private fun forceReportViewport() {
        reportedZoom = zoom
        reportedPanX = panX
        reportedPanY = panY
        val viewport = PreviewViewport(zoom, panX, panY, PreviewScaleMode.FIT)
        post { onViewportChanged?.invoke(viewport) }
    }

    private fun requestFrame(slot: SnapshotSlot, bitmap: Bitmap?) {
        slot.bitmap = bitmap
        val token = ++slot.token
        if (bitmap == null) {
            slot.frame = null
            return
        }
        ensureSnapshotter().execute {
            val frame = PreviewFrame.from(bitmap)
            post {
                if (token != slot.token) return@post
                if (frame == null) {
                    slot.bitmap = null
                    if (slot === resultSlot && !bitmap.isRecycled) {
                        notifyFailure("unable to snapshot preview bitmap")
                    }
                } else {
                    slot.frame = frame
                    if (slot === resultSlot && originalSlot.bitmap === slot.bitmap) {
                        originalSlot.frame = frame
                    }
                }
                pushState()
            }
        }
    }

    private fun ensureSnapshotter(): ExecutorService {
        val existing = snapshotExecutor
        if (existing != null && !existing.isShutdown) return existing
        val created = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND,
                )
                runnable.run()
            }, "GlPreviewSnapshot").apply { isDaemon = true }
        }
        snapshotExecutor = created
        return created
    }

    private fun ensureEngine(): GlEngine {
        val existing = engine
        if (existing != null) return existing
        val created = GlEngine { reason ->
            Log.w(TAG, "GL engine failure: $reason")
            notifyFailure(reason)
        }
        engine = created
        pendingState?.let { created.updateState(it) }
        return created
    }

    private fun notifyFailure(reason: String) {
        post {
            Log.w(TAG, "falling back to Compose renderer: $reason")
            onGlFailure?.invoke()
        }
    }

    private companion object {
        const val TAG = "GlPreview"
    }
}
