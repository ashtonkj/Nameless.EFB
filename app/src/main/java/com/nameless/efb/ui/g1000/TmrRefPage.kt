package com.nameless.efb.ui.g1000

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nameless.efb.data.connectivity.SimSnapshot

/**
 * V-speed reference data from an aircraft profile.
 *
 * All speeds in knots indicated airspeed (KIAS).
 */
data class VspeedRefs(
    val vr: Int? = null,     // Rotation speed
    val vx: Int? = null,     // Best angle of climb
    val vy: Int? = null,     // Best rate of climb
    val vapp: Int? = null,   // Approach speed
    val vfe: Int? = null,    // Max flaps extended
    val vno: Int? = null,    // Max structural cruise
    val vne: Int? = null,    // Never-exceed speed
)

/**
 * G1000 TMR/REF page (G-36).
 *
 * A Compose screen (not OpenGL — this is UI chrome, not a real-time gauge)
 * accessible via the TMRS softkey on the PFD softkey bar.
 *
 * Contents:
 *  - V-speed references from the loaded aircraft profile
 *  - Count-up / count-down timer (shared with AUX Utility)
 *  - Minimums setting (Decision Altitude or Decision Height)
 *  - OAT from sim
 *
 * @param snapshot        Current sim state for OAT display, or null when disconnected.
 * @param vspeeds         V-speed references from the aircraft profile, or null if none loaded.
 * @param minimumsAltFt   Current minimums setting in feet, or null if not set.
 * @param onMinimumsChange  Called when the user adjusts the minimums value.
 */
@Composable
fun TmrRefPage(
    snapshot: SimSnapshot?,
    vspeeds: VspeedRefs? = null,
    minimumsAltFt: Int? = null,
    onMinimumsChange: (Int) -> Unit = {},
) {
    Column(modifier = Modifier.padding(16.dp)) {

        // ── V-speed references ────────────────────────────────────────────────
        Text(
            text = "V-SPEEDS",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF00FF00),  // G1000 green
        )
        Spacer(Modifier.height(4.dp))

        if (vspeeds != null) {
            vspeeds.vr?.let   { VspeedRow("VR",   it) }
            vspeeds.vx?.let   { VspeedRow("VX",   it) }
            vspeeds.vy?.let   { VspeedRow("VY",   it) }
            vspeeds.vapp?.let { VspeedRow("VAPP", it) }
            vspeeds.vfe?.let  { VspeedRow("VFE",  it) }
            vspeeds.vno?.let  { VspeedRow("VNO",  it) }
            vspeeds.vne?.let  { VspeedRow("VNE",  it) }
        } else {
            Text(
                text = "No aircraft profile loaded",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(Modifier.height(8.dp))

        // ── Minimums ─────────────────────────────────────────────────────────
        Text(
            text = "MINIMUMS",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF00FF00),
        )
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "DA/DH",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = minimumsAltFt?.let { "$it ft" } ?: "----",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Cyan,
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(Modifier.height(8.dp))

        // ── OAT ───────────────────────────────────────────────────────────────
        snapshot?.let {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "OAT",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%.1f °C".format(it.oatDegc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun VspeedRow(label: String, kts: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$kts kt",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Cyan,
        )
    }
}
