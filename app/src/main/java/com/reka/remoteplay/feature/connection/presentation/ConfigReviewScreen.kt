package com.reka.remoteplay.feature.connection.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.reka.remoteplay.R
import com.reka.remoteplay.ui.theme.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reka.remoteplay.core.model.*
import com.reka.remoteplay.core.util.ScreenSpecs
import com.reka.remoteplay.core.util.EncoderResolutionCalculator
import com.reka.remoteplay.core.util.QualityPreset
import com.reka.remoteplay.core.util.buildFpsOptions
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import kotlinx.coroutines.delay

@Composable
fun ConfigReviewScreen(
    serverInfo: HardwareInfoMessage,
    suggestedConfig: SuggestedConfigMessage,
    connectionState: ConnectionState = ConnectionState.ConfiguringSettings,
    isPaused: Boolean = false,
    savedMonitors: Int = 1,
    savedFps: Int = 60,
    savedWindowsScale: Int = 125,
    connectionType: String = "Unknown",
    bindMobileScreen: Boolean = false,
    deviceScreenSpecs: ScreenSpecs = ScreenSpecs(1920, 1080, 60f),
    qualityPreset: QualityPreset = QualityPreset.Quality,
    webRtcConnectionType: String = "unknown",
    onBindMobileScreenChanged: (Boolean) -> Unit = {},
    onQualityPresetChanged: (QualityPreset) -> Unit = {},
    onProceed: (monitors: Int, fps: Int, windowsScale: Int) -> Unit,
    onResume: () -> Unit = {},
    onBack: () -> Unit
) {
    val isTurnRelay = webRtcConnectionType == "relay"
    val maxMonitors = serverInfo.monitors.size

    val maxSelectableMonitors = if (maxMonitors <= 3) 3 else maxMonitors
    var selectedMonitors by remember(savedMonitors, maxSelectableMonitors) {
        mutableIntStateOf(savedMonitors.coerceIn(1, maxSelectableMonitors))
    }
    val maxHz = if (bindMobileScreen) deviceScreenSpecs.refreshRate else suggestedConfig.refreshRate.toFloat()
    val availableFpsOptions = buildFpsOptions(maxHz)
    var selectedFps by remember(savedFps, availableFpsOptions) {
        mutableIntStateOf(if (savedFps in availableFpsOptions) savedFps else availableFpsOptions.lastOrNull { it <= 60 } ?: 60)
    }
    val scaleOptions = listOf(100, 125, 150)
    var selectedScale by remember(savedWindowsScale) { mutableIntStateOf(savedWindowsScale) }

    if (isTurnRelay) {
        if (selectedFps > 30) selectedFps = 30
        if (qualityPreset != QualityPreset.Performance) onQualityPresetChanged(QualityPreset.Performance)
    }

    val settingsEnabled = connectionState is ConnectionState.ConfiguringSettings && !isPaused

    // Fixed Toast logic
    val context = LocalContext.current
    val pressBackToDisconnectMsg = stringResource(R.string.press_back_to_disconnect)
    var backPressedOnce by remember { mutableStateOf(false) }
    
    BackHandler {
        if (backPressedOnce) {
            onBack()
        } else {
            backPressedOnce = true
            Toast.makeText(context, pressBackToDisconnectMsg, Toast.LENGTH_SHORT).show()
        }
    }
    
    LaunchedEffect(backPressedOnce) {
        if (backPressedOnce) {
            delay(2000)
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
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (-12).dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }
                Text(
                    text = stringResource(R.string.stream_configuration),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            val networkInfo = suggestedConfig.networkInfo
            InfoCard(
                title = stringResource(R.string.network),
                icon = Icons.Default.NetworkCheck,
                items = buildList {
                    val connLabel = if (isTurnRelay) "$connectionType (TURN Relay)" else "$connectionType (P2P Direct)"
                    add(stringResource(R.string.connection_label) to connLabel)
                    if (networkInfo != null) {
                        add(stringResource(R.string.ping_label) to stringResource(R.string.ping_format, networkInfo.pingMs))
                        add(stringResource(R.string.bandwidth_label) to stringResource(R.string.bandwidth_format, networkInfo.bandwidthMbps))
                        add(stringResource(R.string.jitter_label) to stringResource(R.string.jitter_format, networkInfo.jitterMs))
                    }
                }
            )

            if (isTurnRelay) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppRedBg)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AppYellow, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TURN Relay: P2P failed, streaming via server. Limited to 720p 30FPS to save bandwidth.",
                            color = AppTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.stream_settings), fontWeight = FontWeight.SemiBold, color = AppTextPrimary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.bind_mobile_screen), color = AppTextSecondary, fontSize = 14.sp)
                        Switch(
                            checked = bindMobileScreen,
                            onCheckedChange = { if (settingsEnabled) onBindMobileScreenChanged(it) },
                            enabled = settingsEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AppAccent,
                                uncheckedTrackColor = AppSurface
                            )
                        )
                    }

                    if (bindMobileScreen) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${deviceScreenSpecs.widthPx} x ${deviceScreenSpecs.heightPx} @ ${deviceScreenSpecs.refreshRate.toInt()}Hz",
                            color = AppAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.bind_mobile_description),
                            color = AppTextQuaternary,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Quality Preset", color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QualityPreset.entries.forEach { preset ->
                            val isSelected = qualityPreset == preset
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AppAccent.copy(alpha = 0.2f) else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = settingsEnabled) { onQualityPresetChanged(preset) }
                            ) {
                                Text(
                                    text = preset.displayName,
                                    color = if (isSelected) AppAccent else AppTextTertiary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    val previewW: Int
                    val previewH: Int
                    if (bindMobileScreen) {
                        val landscapeW = maxOf(deviceScreenSpecs.widthPx, deviceScreenSpecs.heightPx)
                        val landscapeH = minOf(deviceScreenSpecs.widthPx, deviceScreenSpecs.heightPx)
                        val (w, h) = EncoderResolutionCalculator.calculate(landscapeW, landscapeH, qualityPreset, serverInfo.maxQualityHeight)
                        previewW = w; previewH = h
                    } else {
                        val sugW = suggestedConfig.resolution.width
                        val sugH = suggestedConfig.resolution.height
                        val (w, h) = EncoderResolutionCalculator.calculate(sugW, sugH, qualityPreset, serverInfo.maxQualityHeight)
                        previewW = w; previewH = h
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Encoder: $previewW × $previewH",
                        color = AppTextQuaternary,
                        fontSize = 12.sp
                    )

                    if (!bindMobileScreen) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(stringResource(R.string.monitors_label), color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (maxSelectableMonitors <= 3) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            (1..3).forEach { count ->
                                SegmentedButton(
                                    selected = selectedMonitors == count,
                                    onClick = { if (settingsEnabled) selectedMonitors = count },
                                    enabled = settingsEnabled,
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = count - 1,
                                        count = 3
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
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            (1..maxSelectableMonitors).forEach { count ->
                                val isSelected = selectedMonitors == count
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) AppAccent.copy(alpha = 0.2f) else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = settingsEnabled) { selectedMonitors = count }
                                ) {
                                    Text(
                                        text = "$count",
                                        color = if (isSelected) AppAccent else AppTextTertiary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(stringResource(R.string.frame_rate), color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableFpsOptions.forEach { fps ->
                            val isSelected = selectedFps == fps
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AppAccent.copy(alpha = 0.2f) else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = settingsEnabled) { selectedFps = fps }
                            ) {
                                Text(
                                    text = "$fps",
                                    color = if (isSelected) AppAccent else AppTextTertiary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Windows Scale", color = AppTextTertiary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        scaleOptions.forEach { scale ->
                            val isSelected = selectedScale == scale
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AppAccent.copy(alpha = 0.2f) else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = settingsEnabled) { selectedScale = scale }
                            ) {
                                Text(
                                    text = "$scale%",
                                    color = if (isSelected) AppAccent else AppTextTertiary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        stringResource(R.string.bitrate_format, suggestedConfig.bitrateKbps / 1000),
                        color = AppTextQuaternary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                        onProceed(selectedMonitors, selectedFps, selectedScale)
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

@Preview(name = "Config Review", showBackground = true)
@Composable
private fun ConfigReviewScreenPreview() {
    RemotePlayTheme {
        ConfigReviewScreen(
            serverInfo = HardwareInfoMessage(
                device = DeviceInfo(
                    name = "Gaming-PC",
                    gpu = "NVIDIA RTX 4080",
                    gpuVramGB = 16,
                    processor = "Intel i9-13900K"
                ),
                encoder = EncoderInfo(type = "NVENC", hwAccel = true),
                monitors = listOf(MonitorInfoDto(0, "Display 1", 1920, 1080)),
                maxQualityHeight = 2160
            ),
            suggestedConfig = SuggestedConfigMessage(
                resolution = ResolutionDto(1920, 1080),
                refreshRate = 60,
                bitrateKbps = 20000,
                selectedCodec = "H264",
                networkInfo = NetworkInfoDto(pingMs = 15.0, bandwidthMbps = 100.0, jitterMs = 2.0)
            ),
            connectionType = "Wi-Fi",
            onProceed = { _, _, _ -> },
            onBack = {}
        )
    }
}
