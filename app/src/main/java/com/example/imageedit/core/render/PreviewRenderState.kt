package com.example.imageedit.core.render

data class PreviewRenderState(
    val result: PreviewFrame?,
    val original: PreviewFrame?,
    val compareMode: Boolean,
    val compareFraction: Float,
    val draft: PreviewRenderDraft?,
    val viewport: PreviewViewport = PreviewViewport(),
)
