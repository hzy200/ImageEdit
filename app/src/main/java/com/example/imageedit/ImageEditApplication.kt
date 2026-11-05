package com.example.imageedit

import android.app.Application
import com.example.imageedit.core.AppGraph
import com.example.imageedit.core.render.RenderSettings

class ImageEditApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        RenderSettings.load(this)
    }
}
