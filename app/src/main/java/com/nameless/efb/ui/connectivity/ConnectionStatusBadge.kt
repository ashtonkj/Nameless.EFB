package com.nameless.efb.ui.connectivity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nameless.efb.data.connectivity.ConnectionStatus

/**
 * A 12dp coloured dot indicating connection state, shown in the top-right
 * corner of each display mode as a Compose overlay over the GL views.
 *
 * - Green  (0xFF00C800): connected and receiving data
 * - Amber  (0xFFFFA500): reconnecting (recent data within 5s)
 * - Red    (0xFFFF0000): disconnected
 */
@Composable
fun ConnectionStatusBadge(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val color = when (status.state) {
        ConnectionStatus.State.CONNECTED    -> Color(0xFF00C800)
        ConnectionStatus.State.RECONNECTING -> Color(0xFFFFA500)
        ConnectionStatus.State.DISCONNECTED -> Color(0xFFFF0000)
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}
