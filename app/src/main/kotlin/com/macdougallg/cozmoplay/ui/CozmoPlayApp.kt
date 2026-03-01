package com.macdougallg.cozmoplay.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.macdougallg.cozmoplay.ui.screens.animations.AnimationsScreen
import com.macdougallg.cozmoplay.ui.screens.connect.ConnectScreen
import com.macdougallg.cozmoplay.ui.screens.cubes.CubesScreen
import com.macdougallg.cozmoplay.ui.screens.drive.DriveScreen
import com.macdougallg.cozmoplay.ui.screens.explore.ExploreScreen
import com.macdougallg.cozmoplay.ui.screens.home.HomeScreen
import com.macdougallg.cozmoplay.ui.screens.onboarding.OnboardingScreen
import com.macdougallg.cozmoplay.ui.screens.settings.SettingsScreen
import com.macdougallg.cozmoplay.ui.navigation.NavRoutes

@Composable
fun CozmoPlayApp(context: Context) {
    val navController = rememberNavController()
    val prefs = context.getSharedPreferences("cozmoplay_settings", Context.MODE_PRIVATE)
    val onboardingDone = prefs.getBoolean("onboarding_complete", false)

    NavHost(navController = navController, startDestination = NavRoutes.CONNECT) {

        composable(NavRoutes.CONNECT) {
            ConnectScreen(
                onConnected = {
                    if (onboardingDone) navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.CONNECT) { inclusive = true }
                    } else navController.navigate(NavRoutes.ONBOARD) {
                        popUpTo(NavRoutes.CONNECT) { inclusive = true }
                    }
                },
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
            )
        }

        composable(NavRoutes.ONBOARD) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.ONBOARD) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateToDrive       = { navController.navigate(NavRoutes.DRIVE) },
                onNavigateToAnimations  = { navController.navigate(NavRoutes.ANIMATIONS) },
                onNavigateToExplore     = { navController.navigate(NavRoutes.EXPLORE) },
                onNavigateToCubes       = { navController.navigate(NavRoutes.CUBES) },
                onNavigateToSettings    = { navController.navigate(NavRoutes.SETTINGS) },
            )
        }

        composable(NavRoutes.DRIVE) {
            DriveScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.ANIMATIONS) {
            AnimationsScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.EXPLORE) {
            ExploreScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.CUBES) {
            CubesScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
