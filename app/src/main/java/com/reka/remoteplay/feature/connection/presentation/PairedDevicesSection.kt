package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reka.remoteplay.feature.connection.data.local.PairedHost
import com.reka.remoteplay.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Paired devices" list (pairing-protocol-contract-v1.md: "Provide unpair/remove-device path").
 * Identity here is the host's DTLS fingerprint (not IP/host — see [PairedHost]), so this is
 * intentionally a separate list from the recent-connections (host+port) section above it.
 */
@Composable
fun PairedDevicesSection(
    pairedHosts: List<PairedHost>,
    onUnpairHost: (String) -> Unit
) {
    if (pairedHosts.isEmpty()) return

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Paired devices",
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
        items(pairedHosts, key = { it.fingerprint }) { host ->
            PairedHostItem(host = host, onUnpair = { onUnpairHost(host.fingerprint) })
        }
    }
}

@Composable
private fun PairedHostItem(host: PairedHost, onUnpair: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AppGreen, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.label.ifEmpty { "Paired host" },
                    color = AppTextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = "Paired ${formatPairedAt(host.pairedAt)}",
                    color = AppTextTertiary,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onUnpair) {
                Icon(Icons.Default.LinkOff, contentDescription = "Unpair", tint = AppRedLight)
            }
        }
    }
}

private fun formatPairedAt(epochMs: Long): String {
    if (epochMs <= 0L) return "recently"
    return try {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
    } catch (_: Exception) {
        "recently"
    }
}
