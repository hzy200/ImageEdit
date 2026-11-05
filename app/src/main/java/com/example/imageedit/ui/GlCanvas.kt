package com.example.imageedit.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.imageedit.core.render.PreviewDraft
import com.example.imageedit.core.render.PreviewViewport

@Composable
fun GlCanvas(
    result: Bitmap?,
    original: Bitmap?,
    compareMode: Boolean,
    compareFraction: Float,
    draft: PreviewDraft?,
    gesturesEnabled: Boolean,
    onViewportChanged: (PreviewViewport) -> Unit,
    onGlFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = rememberUpdatedState(onGlFailure)
    val viewport = rememberUpdatedState(onViewportChanged)
    AndroidView(
        factory = { context ->
            GlPreviewView(context).apply {
                this.onGlFailure = { failure.value() }
                this.onViewportChanged = { viewport.value(it) }
            }
        },
        update = { view ->
            view.gesturesEnabled = gesturesEnabled
            view.update(
                result = result,
                original = original,
                compareMode = compareMode,
                compareFraction = compareFraction,
                draft = draft,
            )
        },
        modifier = modifier,
    )
}
