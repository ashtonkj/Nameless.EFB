package com.nameless.efb.ui.split

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

/**
 * Side-by-side split screen layout with draggable divider (CH-04).
 *
 * [splitRatio] controls the width of the left pane (0.3..0.7).
 * Both panes share the full height. The drag handle is 8dp wide.
 *
 * Typical use: left = approach plate, right = moving map.
 */
@Composable
fun SplitScreenLayout(
    modifier: Modifier = Modifier,
    initialSplitRatio: Float = 0.5f,
    leftContent:  @Composable () -> Unit,
    rightContent: @Composable () -> Unit,
) {
    var splitRatio by remember { mutableFloatStateOf(initialSplitRatio.coerceIn(0.3f, 0.7f)) }
    var totalWidthPx by remember { mutableFloatStateOf(1f) }

    Row(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { totalWidthPx = it.width.toFloat() },
    ) {
        Box(Modifier.weight(splitRatio)) {
            leftContent()
        }

        // Drag handle
        Box(
            Modifier
                .fillMaxHeight()
                .width(8.dp)
                .background(Color(0xFF444466))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        val delta = dragAmount / totalWidthPx
                        splitRatio = (splitRatio + delta).coerceIn(0.3f, 0.7f)
                    }
                }
        )

        Box(Modifier.weight(1f - splitRatio)) {
            rightContent()
        }
    }
}
