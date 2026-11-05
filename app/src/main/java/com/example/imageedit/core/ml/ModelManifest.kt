package com.example.imageedit.core.ml

data class ModelManifest(
    val name: String,
    val version: String,
    val quantization: String,
    val inputSize: Int,
    val backend: String,
    val sha256: String,
    val license: String,
    val assetPath: String,
)

object Models {
    val modnet = ModelManifest(
        name = "MODNet",
        version = "photographic-portrait-matting-2020",
        quantization = "FP32 ONNX",
        inputSize = 512,
        backend = "ONNX Runtime Mobile (XNNPACK, CPU fallback)",
        sha256 = "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9",
        license = "Apache-2.0（MODNet 原始仓库；分发前复核）",
        assetPath = "models/modnet_photographic_portrait_matting.onnx",
    )

    val schpAtr = ModelManifest(
        name = "SCHP-ATR",
        version = "exp-schp-201908301523-atr / INT8 QDQ (QOperator A/B candidate)",
        quantization = "INT8 QDQ",
        inputSize = 512,
        backend = "ONNX Runtime Mobile (CPU EP; QOperator candidate uses XNNPACK)",
        sha256 = "0f8865dee4dda0eab4af2daa49b55b259edfe1dfb5568460b99ffef7a912ed9d",
        license = "MIT（SCHP 原始仓库）",
        assetPath = "models/schp_atr_int8.onnx",
    )
}
