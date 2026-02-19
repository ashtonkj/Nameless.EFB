package com.nameless.efb.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.map.DataBarConfig
import com.nameless.efb.domain.map.DataField
import com.nameless.efb.domain.map.UnitPrefs

/**
 * Instrument strip / data bar (IP-01).
 *
 * Renders a horizontal bar with configurable data fields from [config].
 * Left fields appear on the left, right fields on the right.
 * All fields are read from [snapshot]; null snapshot shows "---" for all.
 *
 * This is a Compose overlay positioned above the GL map view by the caller.
 */
@Composable
fun DataBar(
    snapshot: SimSnapshot?,
    config: DataBarConfig,
    units: UnitPrefs,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left-side fields
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            config.leftFields.forEach { field ->
                DataFieldCell(field = field, snapshot = snapshot, units = units)
            }
        }
        // Right-side fields
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            config.rightFields.forEach { field ->
                DataFieldCell(field = field, snapshot = snapshot, units = units)
            }
        }
    }
}

@Composable
private fun DataFieldCell(
    field: DataField,
    snapshot: SimSnapshot?,
    units: UnitPrefs,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = field.name,
            color = Color(0xFF8888AA),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = field.format(snapshot, units),
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}
