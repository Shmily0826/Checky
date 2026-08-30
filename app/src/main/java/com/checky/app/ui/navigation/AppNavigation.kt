package com.checky.app.ui.navigation

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object History : Screen("history", "History", Icons.Filled.History)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
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
                icon = { Icon(screen.icon, contentDescription = screen.label) },
                label = { Text(text = screen.label) }
            )
        }
    }
}

@Composable
fun CheckyNavHost(
    navController: NavHostController,
    userPreferencesRepository: UserPreferencesRepository,
    startDestination: String
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    NavHost(navController = navController, startDestination = startDestination) {
        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    scope.launch {
                        userPreferencesRepository.setOnboardingComplete(true)
                        navController.navigate(Screen.Home.route) {
                            // Onboarding is finished — it must not come back.
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
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
            route = "connect/{serviceId}",
            arguments = listOf(navArgument("serviceId") { type = NavType.StringType })
        ) {
            ConnectProviderScreen(navController = navController)
        }
    }
}
