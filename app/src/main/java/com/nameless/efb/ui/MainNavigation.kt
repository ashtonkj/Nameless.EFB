package com.nameless.efb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Map", Icons.Default.Map),
    Tab("Gauges", Icons.Default.Speed),
    Tab("G1000", Icons.Default.FlightTakeoff),
)

@Composable
fun MainNavigation() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // Content area — full screen minus tab bar
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (selectedTab) {
                0 -> MapPlaceholder()
                1 -> GaugePlaceholder()
                2 -> G1000Placeholder()
            }
        }

        // Tab bar — 15dp strip per UI-01
        NavigationBar(
            modifier = Modifier.height(56.dp),
            containerColor = Color(0xFF111111),
        ) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                )
            }
        }
    }
}

@Composable
private fun MapPlaceholder() {
    PlaceholderScreen("Flight Planning / Moving Map")
}

@Composable
private fun GaugePlaceholder() {
    PlaceholderScreen("Steam Gauge Panel")
}

@Composable
private fun G1000Placeholder() {
    PlaceholderScreen("G1000 PFD / MFD")
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color(0xFF606060))
    }
}
