package com.checky.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.checky.app.accessibility.TapTapLabTrace
import com.checky.app.data.work.AutoCheckInWorker
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.ui.navigation.CheckyNavHost
import com.checky.app.ui.navigation.Screen
import com.checky.app.ui.theme.CheckyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(Dispatchers.IO) {
            AutoCheckInWorker.reconcileIfStale(this@MainActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        TapTapLabTrace.acknowledgeForegroundReturn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by userPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = UserPreferences())
            CheckyTheme(themeMode = prefs.themeMode) {
                // The start destination must be decided ONCE from the first
                // persisted preferences and stay stable afterwards: a reactive
                // startDestination rebuilds the NavHost graph mid-session and
                // yanks the user off their current screen.
                val startDestination by produceState<String?>(null) {
                    val first = userPreferencesRepository.preferences.first()
                    value = when {
                        BuildConfig.DEBUG && first.onboardingComplete -> "taptap_lab"
                        first.onboardingComplete -> Screen.Home.route
                        else -> "onboarding"
                    }
                }
                val destination = startDestination
                if (destination != null) {
                    val navController = rememberNavController()
                    CheckyNavHost(
                        navController = navController,
                        userPreferencesRepository = userPreferencesRepository,
                        startDestination = destination
                    )
                }
            }
        }
    }
}
