package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reka.remoteplay.ui.theme.AppAccent
import com.reka.remoteplay.ui.theme.AppBackgroundMid
import com.reka.remoteplay.ui.theme.AppGreenLight
import com.reka.remoteplay.ui.theme.AppRedLight
import com.reka.remoteplay.ui.theme.AppYellow

/**
 * Diagnostics card showing P2P connection type, ICE candidate counts and
 * gather duration. Helps the user understand why a cross-NAT connection
 * might be slow or fail.
 *
 * Data sources (all StateFlow from WebRtcManager/PhaseTwoHandler):
 *  - [connectionType]    "host" | "srflx" | "relay" | "prflx" | "failed" | "unknown"
 *  - [hostCount]         host candidates (direct LAN)
 *  - [srflxCount]        server-reflexive (STUN) candidates (P2P via NAT)
 *  - [relayCount]        relay (TURN) candidates
 *  - [prflxCount]        peer-reflexive candidates
 *  - [gatherDurationMs]  time from first candidate to end-of-candidates
 */
@Composable
fun ConnectionDiagnosticsCard(
    connectionType: String,
    hostCount: Int,
    srflxCount: Int,
    relayCount: Int,
    prflxCount: Int,
    gatherDurationMs: Long,
    modifier: Modifier = Modifier
) {
    val isUnknown = connectionType == "unknown" ||
        connectionType == "checking"
    val isHost = connectionType == "host"
    val isSrflx = connectionType == "srflx" || connectionType == "prflx"
    val isRelay = connectionType == "relay"
    val isFailed = connectionType == "failed" || connectionType == "disconnected"

    val statusColor = when {
        isHost || isSrflx || isRelay -> AppGreenLight
        isFailed -> AppRedLight
        isUnknown -> AppYellow
        else -> AppYellow
    }
    val statusLabel = when {
        isHost -> "P2P via LAN (host)"
        isSrflx -> "P2P via STUN (srflx)"
        isRelay -> "P2P via TURN (relay)"
        isFailed -> "P2P failed"
        isUnknown -> "P2P negotiating..."
        else -> "P2P: $connectionType"
    }
    val helpText = when {
        isHost -> "Best — direct LAN connection."
        isSrflx -> "Good — NAT traversed via STUN. UPnP not needed."
        isRelay -> "Uses TURN server bandwidth. Consider UPnP to upgrade to srflx."
        isFailed -> "Both sides may be behind symmetric NAT or 4G. " +
            "Try: (1) enable UPnP on home router, " +
            "(2) move closer to WiFi, (3) wait for TURN/relay support."
        else -> null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppBackgroundMid)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusLabel,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (helpText != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = helpText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatChip("Host", hostCount, AppAccent)
                StatChip("Srflx", srflxCount, AppGreenLight)
                StatChip("Relay", relayCount, AppYellow)
                StatChip("Prflx", prflxCount, AppAccent.copy(alpha = 0.6f))
            }

            if (gatherDurationMs > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ICE gather: ${gatherDurationMs}ms",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = color
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
