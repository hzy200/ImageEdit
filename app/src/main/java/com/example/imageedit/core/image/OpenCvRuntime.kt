package com.example.imageedit.core.image

import android.util.Log
import org.opencv.android.OpenCVLoader

object OpenCvRuntime {
    private const val TAG = "OpenCvRuntime"

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        initialized = runCatching { OpenCVLoader.initLocal() }
            .onFailure { Log.e(TAG, "OpenCV init failed", it) }
            .getOrDefault(false)
        Log.i(TAG, "OpenCV initialized=$initialized")
        return initialized
    }
}
