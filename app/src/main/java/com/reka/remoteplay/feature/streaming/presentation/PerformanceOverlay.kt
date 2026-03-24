package com.reka.remoteplay.feature.streaming.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PerformanceOverlay(
    rttMs: Float,
    activeMonitor: Int,
    monitorCount: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text(
            text = "RTT: ${rttMs.toInt()}ms",
            color = when {
                rttMs < 20 -> Color(0xFF3FB950)
                rttMs < 50 -> Color(0xFFD29922)
                else -> Color(0xFFF85149)
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Monitor: ${activeMonitor + 1}/$monitorCount",
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
