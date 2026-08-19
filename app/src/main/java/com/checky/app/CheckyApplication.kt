package com.checky.app

import android.app.Application
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.data.work.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CheckyApplication : Application() {

    @Inject
    lateinit var repository: CheckInRepository

    private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        // Seed the 3 mock services + their demo results on first launch only.
        seedScope.launch {
            repository.ensureSeeded()
        }
    }
}
