package com.example.imageedit.core.render

import android.app.ActivityManager
import android.content.Context

object GlCapabilities {
    private const val GLES_3_0 = 0x00030000

    fun supportsGles3(context: Context): Boolean = requiredVersion(context) >= GLES_3_0

    fun requiredVersion(context: Context): Int = runCatching {
        context.getSystemService(ActivityManager::class.java)
            ?.deviceConfigurationInfo
            ?.reqGlEsVersion
            ?: 0
    }.getOrDefault(0)

    fun describe(context: Context): String {
        val version = requiredVersion(context)
        return "requiredGles=0x${version.toString(16)} gles3=${version >= GLES_3_0}"
    }
}
