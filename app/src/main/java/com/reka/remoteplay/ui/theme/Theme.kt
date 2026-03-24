package com.reka.remoteplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = darkColorScheme(
    primary = AppAccent,
    secondary = AppTextSecondary,
    tertiary = AppTextTertiary,
    background = AppBackgroundDark,
    surface = AppSurface,
    onPrimary = AppTextPrimary,
    onSecondary = AppTextPrimary,
    onTertiary = AppTextPrimary,
    onBackground = AppTextPrimary,
    onSurface = AppTextPrimary
)

@Composable
fun RemotePlayTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}
