package com.youngsu.slimvideo

import android.app.Application
import com.youngsu.slimvideo.ads.AdManager

class SlimVideoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AdManager.initialize(this)
    }
}
