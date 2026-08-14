package com.reka.remoteplay.app.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.reka.remoteplay.feature.auth.presentation.LoginRoute
import com.reka.remoteplay.feature.connection.presentation.ConfigReviewRoute
import com.reka.remoteplay.feature.connection.presentation.ConnectionRoute
import com.reka.remoteplay.feature.connection.presentation.ConnectionViewModel
import com.reka.remoteplay.feature.connection.presentation.QrScannerRoute
import com.reka.remoteplay.feature.streaming.presentation.StreamingRoute

@Composable
fun AppNavHost(navController: NavHostController) {
    // Own the connection flow above individual destinations so popping the QR scanner does not
    // clear its ViewModel and cancel the connection attempt it just launched.
    val connectionViewModel: ConnectionViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = AppDestination.Connection.route
    ) {
        composable(AppDestination.Login.route) {
            LoginRoute(
                onLoginSuccess = {
                    navController.navigate(AppDestination.Connection.route) {
                        popUpTo(AppDestination.Login.route) { inclusive = true }
                    }
                },
                onSkip = {
                    navController.navigate(AppDestination.Connection.route) {
                        popUpTo(AppDestination.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(AppDestination.Connection.route) {
            ConnectionRoute(
                onNavigateToConfigReview = {
                    navController.navigate(AppDestination.ConfigReview.route)
                },
                onNavigateToQrScanner = {
                    navController.navigate(AppDestination.QrScanner.route)
                },
                viewModel = connectionViewModel
            )
        }

        composable(AppDestination.QrScanner.route) {
            QrScannerRoute(
                onNavigateBack = {
                    navController.popBackStack()
                },
                viewModel = connectionViewModel
            )
        }

        composable(AppDestination.ConfigReview.route) {
            ConfigReviewRoute(
                onNavigateToStreaming = {
                    navController.navigate(AppDestination.Streaming.route)
                },
                onBack = {
                    navController.popBackStack(AppDestination.Connection.route, false)
                },
                viewModel = connectionViewModel
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
