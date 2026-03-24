package com.reka.remoteplay.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.reka.remoteplay.feature.connection.presentation.ConfigReviewRoute
import com.reka.remoteplay.feature.connection.presentation.ConnectionRoute
import com.reka.remoteplay.feature.streaming.presentation.StreamingRoute

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.Connection.route
    ) {
        composable(AppDestination.Connection.route) {
            ConnectionRoute(
                onNavigateToConfigReview = {
                    navController.navigate(AppDestination.ConfigReview.route)
                },
                onNavigateToStreaming = {
                    navController.navigate(AppDestination.Streaming.route) {
                        popUpTo(AppDestination.ConfigReview.route) { inclusive = false }
                    }
                }
            )
        }

        composable(AppDestination.ConfigReview.route) {
            ConfigReviewRoute(
                onNavigateToStreaming = {
                    navController.navigate(AppDestination.Streaming.route) {
                        // Keep ConfigReview in backstack so we can return to it
                        popUpTo(AppDestination.ConfigReview.route) { inclusive = false }
                    }
                },
                onBack = {
                    navController.popBackStack(AppDestination.Connection.route, false)
                }
            )
        }

        composable(AppDestination.Streaming.route) {
            StreamingRoute(
                onBack = {
                    // Go back to ConfigReview (it's still in backstack)
                    navController.popBackStack(AppDestination.ConfigReview.route, false)
                }
            )
        }
    }
}
