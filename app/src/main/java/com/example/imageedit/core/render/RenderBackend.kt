package com.example.imageedit.core.render

import android.content.Context

enum class RenderBackend {
    AUTO,
    GLES,
    CPU,
    ;

    fun allowsGles(): Boolean = this != CPU
}

/**
 * Local-only render backend override used by the debug toggle and tests.
 * AUTO uses GLES when the device reports ES 3.0 and EGL creation succeeds.
 */
object RenderSettings {
    private const val PREFS = "render_settings"
    private const val KEY_BACKEND = "backend"

    @Volatile
    var backend: RenderBackend = RenderBackend.AUTO
        private set

    fun load(context: Context) {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, RenderBackend.AUTO.name)
        backend = RenderBackend.entries.firstOrNull { it.name == name } ?: RenderBackend.AUTO
    }

    fun set(context: Context, value: RenderBackend) {
        backend = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND, value.name)
            .apply()
    }
}
