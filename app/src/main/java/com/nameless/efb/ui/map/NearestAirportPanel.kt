package com.nameless.efb.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.entity.AirportEntity
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.SpatialQuery
import com.nameless.efb.domain.weather.isInGlideRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AirportWithDistance(
    val airport: AirportEntity,
    val distanceNm: Double,
)

/**
 * Nearest airport quick-list panel (IP-04).
 *
 * Shows up to 15 airports within 50 nm of [ownship], sorted by distance.
 * Airports within glide range (1:60 conservative rule) are highlighted green.
 * Tapping a row calls [onDirectTo] for direct-to navigation.
 */
@Composable
fun NearestAirportPanel(
    ownship: LatLon,
    altitudeFt: Float,
    navDb: EfbDatabase,
    onDirectTo: (AirportEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var airports by remember { mutableStateOf(emptyList<AirportWithDistance>()) }

    LaunchedEffect(ownship) {
        withContext(Dispatchers.IO) {
            val raw = navDb.airportDao().nearbyRaw(SpatialQuery.nearbyAirports(ownship, 50.0))
            airports = raw
                .map { ap ->
                    AirportWithDistance(
                        airport = ap,
                        distanceNm = GreatCircle.distanceNm(
                            ownship,
                            LatLon(ap.latitude, ap.longitude),
                        ),
                    )
                }
                .sortedBy { it.distanceNm }
                .take(15)
        }
    }

    LazyColumn(
        modifier = modifier.background(Color(0xCC000000)),
    ) {
        items(airports) { entry ->
            AirportRow(
                entry = entry,
                altitudeFt = altitudeFt,
                onDirectTo = onDirectTo,
            )
        }
    }
}

@Composable
private fun AirportRow(
    entry: AirportWithDistance,
    altitudeFt: Float,
    onDirectTo: (AirportEntity) -> Unit,
) {
    val inGlide = isInGlideRange(entry.distanceNm, altitudeFt)
    val textColor = if (inGlide) Color(0xFF00FF88) else Color.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDirectTo(entry.airport) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.airport.icao,
            color = textColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.airport.name,
            color = textColor.copy(alpha = 0.75f),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "%.1f nm".format(entry.distanceNm),
            color = textColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
