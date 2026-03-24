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
                }
            )
        }

        composable(AppDestination.ConfigReview.route) {
            ConfigReviewRoute(
                onNavigateToStreaming = {
                    navController.navigate(AppDestination.Streaming.route)
                },
                onBack = {
                    navController.popBackStack(AppDestination.Connection.route, false)
                }
            )
        }

        composable(AppDestination.Streaming.route) {
            StreamingRoute(
                onBack = {
                    navController.popBackStack(AppDestination.ConfigReview.route, false)
                }
            )
        }
    }
}
