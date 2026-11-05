package com.example.imageedit.core

import android.content.Context

/**
 * Minimal application-scoped dependency holder for model/runtime singletons.
 * Kept intentionally small; replace with proper DI if the app grows.
 */
object AppGraph {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
