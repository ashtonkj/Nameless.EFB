package com.nameless.efb.ui.steam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nameless.efb.domain.gauge.GaugeLayoutItem
import com.nameless.efb.domain.gauge.GaugeType

/**
 * Compose drag-and-drop editor for the custom steam gauge layout (SG-21).
 *
 * Displays all 14 gauge types in a configurable grid.  The user can drag
 * items between cells to reorder the layout.  Changes are committed via
 * [onLayoutChanged] which persists to Room.
 *
 * This is a static configuration screen — Compose is permitted here.
 */
@Composable
fun GaugeLayoutEditor(
    layout: List<GaugeLayoutItem>,
    onLayoutChanged: (List<GaugeLayoutItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var workingLayout by remember { mutableStateOf(layout) }
    var dragging by remember { mutableStateOf<GaugeType?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Gauge Layout",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(workingLayout, key = { it.gaugeType }) { item ->
                GaugeCell(
                    item = item,
                    isBeingDragged = dragging == item.gaugeType,
                    onDragStart = { dragging = item.gaugeType },
                    onDrop = { targetType ->
                        workingLayout = swapGauges(workingLayout, dragging, targetType)
                        dragging = null
                    },
                    modifier = Modifier
                        .height(100.dp)
                        .padding(4.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { workingLayout = layout }) {
                Text("Reset")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onLayoutChanged(workingLayout) }) {
                Text("Apply")
            }
        }
    }
}

@Composable
private fun GaugeCell(
    item: GaugeLayoutItem,
    isBeingDragged: Boolean,
    onDragStart: () -> Unit,
    onDrop: (GaugeType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(if (isBeingDragged) Color(0xFF333333) else Color(0xFF1A1A1A))
            .border(1.dp, if (isBeingDragged) Color.Cyan else Color(0xFF444444))
            .pointerInput(item.gaugeType) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd   = { onDrop(item.gaugeType) },
                    onDrag      = { _, _ -> },
                )
            },
    ) {
        Text(
            text = item.gaugeType.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

private fun swapGauges(
    layout: List<GaugeLayoutItem>,
    from: GaugeType?,
    to: GaugeType?,
): List<GaugeLayoutItem> {
    if (from == null || to == null || from == to) return layout
    val fromItem = layout.firstOrNull { it.gaugeType == from } ?: return layout
    val toItem   = layout.firstOrNull { it.gaugeType == to }   ?: return layout
    return layout.map { item ->
        when (item.gaugeType) {
            from -> item.copy(gridCol = toItem.gridCol, gridRow = toItem.gridRow)
            to   -> item.copy(gridCol = fromItem.gridCol, gridRow = fromItem.gridRow)
            else -> item
        }
    }
}
