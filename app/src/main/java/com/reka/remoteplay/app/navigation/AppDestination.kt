package com.reka.remoteplay.app.navigation

sealed class AppDestination(val route: String) {
    data object Connection : AppDestination("connection")
    data object ConfigReview : AppDestination("config_review")
    data object Streaming : AppDestination("streaming")
}
