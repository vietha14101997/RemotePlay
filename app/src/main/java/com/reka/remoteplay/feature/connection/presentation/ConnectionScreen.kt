package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reka.remoteplay.feature.connection.data.local.SavedServer
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ConnectionScreen(
    connectionState: ConnectionState,
    savedServers: List<SavedServer>,
    hostInput: String,
    portInput: String,
    usbMode: Boolean,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectToServer: (SavedServer) -> Unit,
    onRemoveServer: (SavedServer) -> Unit,
    onSetUsbMode: (Boolean) -> Unit
) {
    val isConnecting = connectionState.isConnected

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AppBackgroundDark,
                        AppBackgroundMid,
                        AppBackgroundDark
                    )
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
                text = "Remote Play",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Connect to your PC",
                fontSize = 14.sp,
                color = AppTextTertiary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Connection form
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Host input
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = onHostChanged,
                        label = { Text("Server IP") },
                        placeholder = { Text("192.168.1.100") },
                        leadingIcon = { Icon(Icons.Default.Computer, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        enabled = !isConnecting,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppAccent,
                            unfocusedBorderColor = AppBorder,
                            focusedLabelColor = AppAccent,
                            unfocusedLabelColor = AppTextTertiary,
                            cursorColor = AppAccent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = AppTextSecondary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Port input
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = onPortChanged,
                        label = { Text("Port") },
                        leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (!isConnecting) onConnect() }
                        ),
                        enabled = !isConnecting,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppAccent,
                            unfocusedBorderColor = AppBorder,
                            focusedLabelColor = AppAccent,
                            unfocusedLabelColor = AppTextTertiary,
                            cursorColor = AppAccent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = AppTextSecondary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // USB Mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Usb,
                                contentDescription = null,
                                tint = AppTextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("USB Tethering", color = AppTextSecondary, fontSize = 14.sp)
                        }
                        Switch(
                            checked = usbMode,
                            onCheckedChange = onSetUsbMode,
                            enabled = !isConnecting,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppAccent,
                                checkedTrackColor = AppAccent.copy(alpha = 0.3f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Connect / Disconnect button
                    Button(
                        onClick = {
                            if (isConnecting) onDisconnect() else onConnect()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnecting) AppRed else AppGreen
                        )
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Disconnect", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Connection progress
            AnimatedVisibility(visible = isConnecting) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
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
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = AppRed
                        )
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

            // Recent connections
            if (savedServers.isNotEmpty()) {
                Text(
                    text = "RECENT CONNECTIONS",
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
                            enabled = !isConnecting
                        )
                    }
                }
            }
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
                    color = Color.White,
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
                    contentDescription = "Remove",
                    tint = AppTextQuaternary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
