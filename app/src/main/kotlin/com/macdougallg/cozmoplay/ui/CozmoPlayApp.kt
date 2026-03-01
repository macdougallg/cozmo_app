package com.macdougallg.cozmoplay.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.macdougallg.cozmoplay.ui.navigation.NavRoutes
import com.macdougallg.cozmoplay.ui.screens.connect.ConnectScreen

/**
 * Root composable. Owns the NavHost and wires all screen destinations.
 *
 * Navigation rules (UI PRD section 2.4):
 * - Entry point is always ConnectScreen
 * - Navigation events come from ViewModels via SharedFlow, never from composables directly
 * - Max depth of 2 taps from HomeScreen to any feature
 *
 * TODO Agent 3: Replace placeholder screens with real implementations.
 */
@Composable
fun CozmoPlayApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.CONNECT,
    ) {
        composable(NavRoutes.CONNECT) {
            ConnectScreen(
                onConnected = { navController.navigate(NavRoutes.HOME) },
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
            )
        }

        composable(NavRoutes.ONBOARD) {
            // TODO Agent 3: Implement OnboardingScreen
            PlaceholderScreen("Onboarding")
        }

        composable(NavRoutes.HOME) {
            // TODO Agent 3: Implement HomeScreen
            PlaceholderScreen("Home")
        }

        composable(NavRoutes.DRIVE) {
            // TODO Agent 3: Implement DriveScreen
            PlaceholderScreen("Drive")
        }

        composable(NavRoutes.ANIMATIONS) {
            // TODO Agent 3: Implement AnimationsScreen
            PlaceholderScreen("Animations")
        }

        composable(NavRoutes.EXPLORE) {
            // TODO Agent 3: Implement ExploreScreen
            PlaceholderScreen("Explore")
        }

        composable(NavRoutes.CUBES) {
            // TODO Agent 3: Implement CubesScreen
            PlaceholderScreen("Cubes")
        }

        composable(NavRoutes.SETTINGS) {
            // TODO Agent 3: Implement SettingsScreen
            PlaceholderScreen("Settings")
        }
    }
}
