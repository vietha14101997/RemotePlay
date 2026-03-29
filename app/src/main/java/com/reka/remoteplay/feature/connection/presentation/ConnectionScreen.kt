package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.reka.remoteplay.R
import com.reka.remoteplay.feature.connection.data.local.SavedServer
import com.reka.remoteplay.feature.connection.data.remote.ServerDiscoveryService
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ConnectionScreen(
    connectionState: ConnectionState,
    savedServers: List<SavedServer>,
    discoveredServers: List<ServerDiscoveryService.DiscoveredServer>,
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectToServer: (SavedServer) -> Unit,
    onConnectToDiscovered: (ServerDiscoveryService.DiscoveredServer) -> Unit,
    onRemoveServer: (SavedServer) -> Unit,
    onScanQr: () -> Unit = {},
    guestDeviceId: String = "",
    guestPassword: String = "",
    guestError: String? = null,
    guestConnecting: Boolean = false,
    onGuestDeviceIdChange: (String) -> Unit = {},
    onGuestPasswordChange: (String) -> Unit = {},
    onGuestConnect: () -> Unit = {}
) {
    val isBusy = connectionState.isConnected

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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Title
            Icon(
                imageVector = Icons.Default.DesktopWindows,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Text(
                text = stringResource(R.string.connect_to_pc),
                fontSize = 14.sp,
                color = AppTextTertiary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Server discovery
            if (!isBusy) {
                if (!isScanning && discoveredServers.isEmpty()) {
                    // Find Server + Scan QR buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onStartScan,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppAccent)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.find_server), fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = onScanQr,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppAccent)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = AppAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan QR", fontWeight = FontWeight.SemiBold, color = AppAccent)
                        }
                    }
                } else if (isScanning && discoveredServers.isEmpty()) {
                    // Scanning in progress
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AppSurface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = AppAccent,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.searching_servers), color = AppTextTertiary, fontSize = 14.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.available_servers),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTextTertiary
                        )
                        if (isScanning) {
                            IconButton(onClick = onStopScan, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, stringResource(R.string.stop), tint = AppTextTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    discoveredServers.forEach { server ->
                        DiscoveredServerItem(
                            server = server,
                            onClick = { onConnectToDiscovered(server) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Connection progress
            AnimatedVisibility(visible = isBusy) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = connectionState.description,
                            color = AppAccent,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { connectionState.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = AppAccent,
                            trackColor = AppBorder
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onDisconnect,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppRed)
                        ) {
                            Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Error display
            if (connectionState is ConnectionState.Error) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppRedBg)
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = AppRed)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = connectionState.message,
                            color = AppRedLight,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connect by ID (UltraViewer-style)
            if (!isBusy) {
                Text(
                    text = "CONNECT BY ID",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = guestDeviceId,
                                onValueChange = { if (it.length <= 9) onGuestDeviceIdChange(it.uppercase()) },
                                label = { Text("Room ID") },
                                placeholder = { Text("XXX-XXX") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppAccent,
                                    unfocusedBorderColor = AppBorder,
                                    focusedLabelColor = AppAccent,
                                    unfocusedLabelColor = AppTextTertiary,
                                    cursorColor = AppAccent
                                )
                            )
                            OutlinedTextField(
                                value = guestPassword,
                                onValueChange = { if (it.length <= 6) onGuestPasswordChange(it) },
                                label = { Text("Password") },
                                placeholder = { Text("######") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppAccent,
                                    unfocusedBorderColor = AppBorder,
                                    focusedLabelColor = AppAccent,
                                    unfocusedLabelColor = AppTextTertiary,
                                    cursorColor = AppAccent
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = onGuestConnect,
                            enabled = guestDeviceId.isNotBlank() && guestPassword.isNotBlank() && !guestConnecting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppGreen)
                        ) {
                            if (guestConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = AppTextPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Link, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connect", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (guestError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = guestError,
                                color = AppRedLight,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Recent connections
            if (savedServers.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.recent_connections),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedServers) { server ->
                        SavedServerItem(
                            server = server,
                            onClick = { onConnectToServer(server) },
                            onDelete = { onRemoveServer(server) },
                            enabled = !isBusy
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredServerItem(
    server: ServerDiscoveryService.DiscoveredServer,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                tint = AppGreen,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name.ifEmpty { server.ip },
                    color = AppTextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = "${server.ip}:${server.port}",
                    color = AppTextTertiary,
                    fontSize = 12.sp
                )
            }
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                tint = AppGreen,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SavedServerItem(
    server: SavedServer,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (server.name.isNotEmpty()) server.name else server.host,
                    color = AppTextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = "${server.host}:${server.port}",
                    color = AppTextTertiary,
                    fontSize = 12.sp
                )
                if (server.lastConnected > 0) {
                    Text(
                        text = dateFormat.format(Date(server.lastConnected)),
                        color = AppTextQuaternary,
                        fontSize = 11.sp
                    )
                }
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.remove),
                    tint = AppTextQuaternary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
