package com.checky.app

import android.app.Application
import com.checky.app.data.work.NotificationHelper
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CheckyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }
}
