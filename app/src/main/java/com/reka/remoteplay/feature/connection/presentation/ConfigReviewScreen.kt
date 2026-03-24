package com.reka.remoteplay.feature.connection.presentation

import androidx.activity.compose.BackHandler
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

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.reka.remoteplay.R
import com.reka.remoteplay.ui.theme.*
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
    savedMonitors: Int = 1,
    savedResolution: Int = 1080,
    savedFps: Int = 60,
    connectionType: String = "Unknown",
    onProceed: (monitors: Int, resolutionHeight: Int, fps: Int) -> Unit,
    onResume: () -> Unit = {},
    onBack: () -> Unit
) {
    val maxMonitors = serverInfo.monitors.size
    val maxNativeHeight = suggestedConfig.maxNativeHeight
    val availableResolutions = buildList {
        add(720)
        add(1080)
        if (maxNativeHeight >= 1440) add(1440)
        if (maxNativeHeight >= 2160) add(2160)
    }

    // Use saved settings as defaults, clamped to server capabilities.
    // Key on saved values so remember re-evaluates when DataStore loads.
    var selectedMonitors by remember(savedMonitors, maxMonitors) {
        mutableIntStateOf(savedMonitors.coerceIn(1, maxMonitors))
    }
    var selectedResolution by remember(savedResolution) {
        val clamped = if (savedResolution in availableResolutions) savedResolution else 1080
        mutableIntStateOf(clamped)
    }
    var selectedFps by remember(savedFps) {
        mutableIntStateOf(savedFps.coerceIn(30, 60))
    }

    val settingsEnabled = connectionState is ConnectionState.ConfiguringSettings && !isPaused

    // Double-press back to disconnect
    val context = androidx.compose.ui.platform.LocalContext.current
    var backPressedOnce by remember { mutableStateOf(false) }
    BackHandler {
        if (backPressedOnce) {
            onBack()
        } else {
            backPressedOnce = true
            android.widget.Toast.makeText(context, context.getString(R.string.press_back_to_disconnect), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(backPressedOnce) {
        if (backPressedOnce) {
            kotlinx.coroutines.delay(2000)
            backPressedOnce = false
        }
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = AppTextTertiary)
                }
                Text(stringResource(R.string.stream_configuration), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server Info Card
            InfoCard(
                title = stringResource(R.string.server),
                icon = Icons.Default.Computer,
                items = listOf(
                    stringResource(R.string.name_label) to serverInfo.device.name,
                    stringResource(R.string.gpu_label) to stringResource(R.string.gpu_format, serverInfo.device.gpu, serverInfo.device.gpuVramGB),
                    stringResource(R.string.cpu_label) to serverInfo.device.processor,
                    stringResource(R.string.encoder_label) to stringResource(R.string.encoder_format, serverInfo.encoder.type, if (serverInfo.encoder.hwAccel) stringResource(R.string.hardware) else stringResource(R.string.software)),
                    stringResource(R.string.monitors_label) to stringResource(R.string.displays_format, serverInfo.monitors.size)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Network Info Card — use client-detected connectionType
            val networkInfo = suggestedConfig.networkInfo
            InfoCard(
                title = stringResource(R.string.network),
                icon = Icons.Default.NetworkCheck,
                items = buildList {
                    add(stringResource(R.string.connection_label) to connectionType)
                    if (networkInfo != null) {
                        add(stringResource(R.string.ping_label) to stringResource(R.string.ping_format, networkInfo.pingMs))
                        add(stringResource(R.string.bandwidth_label) to stringResource(R.string.bandwidth_format, networkInfo.bandwidthMbps))
                        add(stringResource(R.string.jitter_label) to stringResource(R.string.jitter_format, networkInfo.jitterMs))
                    }
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Stream Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.stream_settings), fontWeight = FontWeight.SemiBold, color = AppTextPrimary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Monitor count
                    Text(stringResource(R.string.monitors_label), color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        (1..maxMonitors.coerceAtMost(4)).forEach { count ->
                            SegmentedButton(
                                selected = selectedMonitors == count,
                                onClick = { if (settingsEnabled) selectedMonitors = count },
                                enabled = settingsEnabled,
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
                    Text(stringResource(R.string.resolution), color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        availableResolutions.forEachIndexed { index, res ->
                            SegmentedButton(
                                selected = selectedResolution == res,
                                onClick = { if (settingsEnabled) selectedResolution = res },
                                enabled = settingsEnabled,
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
                                Text(stringResource(R.string.resolution_format, res))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // FPS
                    Text(stringResource(R.string.frame_rate), color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(30, 60).forEachIndexed { index, fps ->
                            SegmentedButton(
                                selected = selectedFps == fps,
                                onClick = { if (settingsEnabled) selectedFps = fps },
                                enabled = settingsEnabled,
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = AppAccent.copy(alpha = 0.2f),
                                    activeContentColor = AppAccent,
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = AppTextTertiary
                                )
                            ) {
                                Text(stringResource(R.string.fps_format, fps))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bitrate info (read-only)
                    Text(
                        stringResource(R.string.bitrate_format, suggestedConfig.bitrateKbps / 1000),
                        color = AppTextQuaternary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Start / Resume button
            val isInProgress = connectionState !is ConnectionState.ConfiguringSettings && !isPaused
            val buttonText = when {
                isPaused -> stringResource(R.string.resume)
                connectionState is ConnectionState.ConfiguringSettings -> stringResource(R.string.start)
                connectionState is ConnectionState.SendingDisplayConfig -> stringResource(R.string.setting_up)
                connectionState is ConnectionState.AwaitingSetupComplete -> stringResource(R.string.setting_up)
                connectionState is ConnectionState.IceNegotiating -> stringResource(R.string.connecting)
                connectionState is ConnectionState.ReadyToStream -> stringResource(R.string.starting)
                connectionState is ConnectionState.StartingStream -> stringResource(R.string.starting)
                else -> stringResource(R.string.start)
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
                        color = AppTextPrimary,
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
                Text(buttonText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppTextPrimary)
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
                Text(title, fontWeight = FontWeight.SemiBold, color = AppTextPrimary, fontSize = 15.sp)
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
