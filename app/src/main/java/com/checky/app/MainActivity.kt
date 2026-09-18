package com.checky.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.checky.app.accessibility.TapTapLabTrace
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.ui.navigation.CheckyNavHost
import com.checky.app.ui.navigation.Screen
import com.checky.app.ui.theme.CheckyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

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
                val navController = rememberNavController()
                CheckyNavHost(
                    navController = navController,
                    userPreferencesRepository = userPreferencesRepository,
                    startDestination = when {
                        BuildConfig.DEBUG -> "taptap_lab"
                        prefs.onboardingComplete -> Screen.Home.route
                        else -> "onboarding"
                    }
                )
            }
        }
    }
}
