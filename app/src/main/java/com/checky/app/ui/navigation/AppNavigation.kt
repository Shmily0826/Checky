package com.checky.app.ui.navigation

import com.checky.app.BuildConfig
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.ui.screens.onboarding.OnboardingScreen
import com.checky.app.ui.screens.home.HomeScreen
import com.checky.app.ui.screens.history.HistoryScreen
import com.checky.app.ui.screens.settings.SettingsScreen
import com.checky.app.ui.screens.addservice.AddServiceScreen
import com.checky.app.ui.screens.connect.ConnectProviderScreen
import com.checky.app.ui.screens.provider.ProviderDetailsScreen
import com.checky.app.ui.screens.taptaplab.TapTapLabScreen
import androidx.compose.ui.res.stringResource
import com.checky.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val icon: ImageVector) {
    data object Home : Screen("home", Icons.Filled.Home)
    data object History : Screen("history", Icons.Filled.History)
    data object Settings : Screen("settings", Icons.Filled.Settings)
}

val bottomTabs = listOf(Screen.Home, Screen.History, Screen.Settings)

@Composable
fun CheckyBottomBar(navController: NavHostController) {
    NavigationBar {
        val current = navController.currentDestination
        bottomTabs.forEach { screen ->
            val selected = current?.hierarchy?.any { it.route == screen.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        screen.icon,
                        contentDescription = stringResource(screen.labelRes())
                    )
                },
                label = { Text(text = stringResource(screen.labelRes())) }
            )
        }
    }
}

private fun Screen.labelRes(): Int = when (this) {
    Screen.Home -> R.string.nav_home
    Screen.History -> R.string.nav_history
    Screen.Settings -> R.string.nav_settings
}

@Composable
fun CheckyNavHost(
    navController: NavHostController,
    userPreferencesRepository: UserPreferencesRepository,
    startDestination: String
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    NavHost(navController = navController, startDestination = startDestination) {
        if (BuildConfig.DEBUG) {
            composable("taptap_lab") {
                TapTapLabScreen()
            }
        }
        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    // Navigate on the main thread first; the preference write
                    // is async — a suspend continuation may resume on an IO
                    // thread and NavController asserts the main thread.
                    navController.navigate(Screen.Home.route) {
                        // Onboarding is finished — it must not come back.
                        popUpTo("onboarding") { inclusive = true }
                    }
                    scope.launch { userPreferencesRepository.setOnboardingComplete(true) }
                }
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.History.route) {
            HistoryScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable("add_service") {
            AddServiceScreen(navController = navController)
        }
        composable(
            route = "provider_details/{serviceId}",
            arguments = listOf(navArgument("serviceId") { type = NavType.StringType })
        ) {
            ProviderDetailsScreen(navController = navController)
        }
        composable(
            route = "connect/{serviceId}?reconnect={reconnect}",
            arguments = listOf(
                navArgument("serviceId") { type = NavType.StringType },
                navArgument("reconnect") { type = NavType.BoolType; defaultValue = false }
            )
        ) {
            ConnectProviderScreen(navController = navController)
        }
    }
}
