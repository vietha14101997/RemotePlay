package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.reka.remoteplay.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reka.remoteplay.core.model.HardwareInfoMessage
import com.reka.remoteplay.core.model.SuggestedConfigMessage
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState

@Composable
fun ConfigReviewScreen(
    serverInfo: HardwareInfoMessage,
    suggestedConfig: SuggestedConfigMessage,
    connectionState: ConnectionState = ConnectionState.ConfiguringSettings,
    isPaused: Boolean = false,
    onProceed: (monitors: Int, resolutionHeight: Int, fps: Int) -> Unit,
    onResume: () -> Unit = {},
    onBack: () -> Unit
) {
    var selectedMonitors by remember { mutableIntStateOf(suggestedConfig.monitors.coerceAtMost(3)) }
    var selectedResolution by remember { mutableIntStateOf(suggestedConfig.resolution.height) }
    var selectedFps by remember { mutableIntStateOf(suggestedConfig.fps) }

    val maxMonitors = serverInfo.monitors.size
    val maxNativeHeight = suggestedConfig.maxNativeHeight
    val availableResolutions = buildList {
        add(720)
        add(1080)
        if (maxNativeHeight >= 1440) add(1440)
        if (maxNativeHeight >= 2160) add(2160)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(AppBackgroundDark, AppBackgroundMid, AppBackgroundDark)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AppTextTertiary)
                }
                Text("Stream Configuration", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server Info Card
            InfoCard(
                title = "Server",
                icon = Icons.Default.Computer,
                items = listOf(
                    "Name" to serverInfo.device.name,
                    "GPU" to "${serverInfo.device.gpu} (${serverInfo.device.gpuVramGB}GB)",
                    "CPU" to serverInfo.device.processor,
                    "Encoder" to "${serverInfo.encoder.type} (${if (serverInfo.encoder.hwAccel) "Hardware" else "Software"})",
                    "Monitors" to "${serverInfo.monitors.size} displays"
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Network Info Card
            val networkInfo = suggestedConfig.networkInfo
            InfoCard(
                title = "Network",
                icon = Icons.Default.NetworkCheck,
                items = buildList {
                    add("Connection" to suggestedConfig.connectionType)
                    if (networkInfo != null) {
                        add("Ping" to "${String.format(java.util.Locale.US, "%.1f", networkInfo.pingMs)} ms")
                        add("Bandwidth" to "${String.format(java.util.Locale.US, "%.1f", networkInfo.bandwidthMbps)} Mbps")
                        add("Jitter" to "${String.format(java.util.Locale.US, "%.1f", networkInfo.jitterMs)} ms")
                        if (networkInfo.isUsbMode) {
                            add("USB" to "${networkInfo.usbVersion ?: "Unknown"} (${String.format(java.util.Locale.US, "%.0f", networkInfo.usbEstimatedBandwidthMbps)} Mbps)")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Codec chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            "Codec: ${suggestedConfig.selectedCodec}",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.VideoSettings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (suggestedConfig.selectedCodec == "H265") AppGreenBg else AppSurface,
                        labelColor = if (suggestedConfig.selectedCodec == "H265") AppGreenLight else AppTextSecondary
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Stream Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Stream Settings", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Monitor count
                    Text("Monitors", color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        (1..maxMonitors.coerceAtMost(4)).forEach { count ->
                            SegmentedButton(
                                selected = selectedMonitors == count,
                                onClick = { selectedMonitors = count },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = count - 1,
                                    count = maxMonitors.coerceAtMost(4)
                                ),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = AppAccent.copy(alpha = 0.2f),
                                    activeContentColor = AppAccent,
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = AppTextTertiary
                                )
                            ) {
                                Text("$count")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Resolution
                    Text("Resolution", color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        availableResolutions.forEachIndexed { index, res ->
                            SegmentedButton(
                                selected = selectedResolution == res,
                                onClick = { selectedResolution = res },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = availableResolutions.size
                                ),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = AppAccent.copy(alpha = 0.2f),
                                    activeContentColor = AppAccent,
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = AppTextTertiary
                                )
                            ) {
                                Text("${res}p")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // FPS
                    Text("Frame Rate", color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(30, 60).forEachIndexed { index, fps ->
                            SegmentedButton(
                                selected = selectedFps == fps,
                                onClick = { selectedFps = fps },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = AppAccent.copy(alpha = 0.2f),
                                    activeContentColor = AppAccent,
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = AppTextTertiary
                                )
                            ) {
                                Text("${fps} FPS")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bitrate info (read-only)
                    Text(
                        "Bitrate: ${suggestedConfig.bitrateKbps / 1000} Mbps (auto)",
                        color = AppTextQuaternary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Start / Resume button with state progression
            val isInProgress = connectionState !is ConnectionState.ConfiguringSettings && !isPaused
            val buttonText = when {
                isPaused -> "Resume"
                connectionState is ConnectionState.ConfiguringSettings -> "Start"
                connectionState is ConnectionState.SendingDisplayConfig -> "Setting up..."
                connectionState is ConnectionState.AwaitingSetupComplete -> "Setting up..."
                connectionState is ConnectionState.IceNegotiating -> "Connecting..."
                connectionState is ConnectionState.ReadyToStream -> "Starting..."
                connectionState is ConnectionState.StartingStream -> "Starting..."
                else -> "Start"
            }
            val buttonColor = when {
                isPaused -> AppBlue
                isInProgress -> AppBlue
                else -> AppGreen
            }

            Button(
                onClick = {
                    if (isPaused) {
                        onResume()
                    } else if (!isInProgress) {
                        onProceed(selectedMonitors, selectedResolution, selectedFps)
                    }
                },
                enabled = !isInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    disabledContainerColor = buttonColor
                )
            ) {
                if (isInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Rocket,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(buttonText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    icon: ImageVector,
    items: List<Pair<String, String>>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AppAccent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = AppTextTertiary, fontSize = 13.sp)
                    Text(value, color = AppTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
