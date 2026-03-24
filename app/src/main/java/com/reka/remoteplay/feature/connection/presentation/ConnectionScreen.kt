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
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onConnected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val savedServers by viewModel.savedServers.collectAsState(initial = emptyList())
    val hostInput by viewModel.hostInput.collectAsState()
    val portInput by viewModel.portInput.collectAsState()
    val usbMode by viewModel.usbMode.collectAsState(initial = false)

    val isConnecting = connectionState.isConnected && !connectionState.isStreaming

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D1117),
                        Color(0xFF161B22),
                        Color(0xFF0D1117)
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
                tint = Color(0xFF58A6FF),
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
                color = Color(0xFF8B949E)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Connection form
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Host input
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = viewModel::onHostChanged,
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
                            focusedBorderColor = Color(0xFF58A6FF),
                            unfocusedBorderColor = Color(0xFF30363D),
                            focusedLabelColor = Color(0xFF58A6FF),
                            unfocusedLabelColor = Color(0xFF8B949E),
                            cursorColor = Color(0xFF58A6FF),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color(0xFFC9D1D9)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Port input
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = viewModel::onPortChanged,
                        label = { Text("Port") },
                        leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (!isConnecting) viewModel.connect() }
                        ),
                        enabled = !isConnecting,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF58A6FF),
                            unfocusedBorderColor = Color(0xFF30363D),
                            focusedLabelColor = Color(0xFF58A6FF),
                            unfocusedLabelColor = Color(0xFF8B949E),
                            cursorColor = Color(0xFF58A6FF),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color(0xFFC9D1D9)
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
                                tint = Color(0xFF8B949E),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("USB Tethering", color = Color(0xFFC9D1D9), fontSize = 14.sp)
                        }
                        Switch(
                            checked = usbMode,
                            onCheckedChange = viewModel::setUsbMode,
                            enabled = !isConnecting,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF58A6FF),
                                checkedTrackColor = Color(0xFF58A6FF).copy(alpha = 0.3f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Connect / Disconnect button
                    Button(
                        onClick = {
                            if (isConnecting) viewModel.disconnect() else viewModel.connect()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnecting) Color(0xFFDA3633) else Color(0xFF238636)
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = connectionState.description,
                            color = Color(0xFF58A6FF),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { connectionState.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF58A6FF),
                            trackColor = Color(0xFF30363D)
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3D1F1F))
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFDA3633)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = (connectionState as ConnectionState.Error).message,
                            color = Color(0xFFF85149),
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
                    color = Color(0xFF8B949E),
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
                            onClick = { viewModel.connectToServer(server) },
                            onDelete = { viewModel.removeServer(server) },
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))
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
                tint = Color(0xFF58A6FF),
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
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp
                )
                if (server.lastConnected > 0) {
                    Text(
                        text = dateFormat.format(Date(server.lastConnected)),
                        color = Color(0xFF484F58),
                        fontSize = 11.sp
                    )
                }
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = Color(0xFF484F58),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

