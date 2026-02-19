package com.nameless.efb.data.charts

/**
 * Geo-referencing data for a navigational chart or approach plate (CH-01).
 *
 * Maps plate pixel coordinates to geographic coordinates using a simple
 * linear (affine) model — adequate for georectified plates covering small
 * areas (<2° × 2°) where Mercator distortion is negligible.
 */
data class PlateGeoRef(
    val widthPx: Int,
    val heightPx: Int,
    val topLeftLat: Double,
    val topLeftLon: Double,
    val bottomRightLat: Double,
    val bottomRightLon: Double,
)

/**
 * Returns the (x, y) pixel position of [latDeg]/[lonDeg] within the plate.
 *
 * Returns null if the position is outside the plate bounds.
 */
fun latLonToPlatePixel(latDeg: Double, lonDeg: Double, geoRef: PlateGeoRef): Pair<Float, Float>? {
    val latSpan = geoRef.topLeftLat - geoRef.bottomRightLat
    val lonSpan = geoRef.bottomRightLon - geoRef.topLeftLon
    if (latSpan <= 0.0 || lonSpan <= 0.0) return null

    val x = ((lonDeg - geoRef.topLeftLon) / lonSpan * geoRef.widthPx).toFloat()
    val y = ((geoRef.topLeftLat - latDeg)  / latSpan * geoRef.heightPx).toFloat()

    if (x < 0f || x > geoRef.widthPx || y < 0f || y > geoRef.heightPx) return null
    return Pair(x, y)
}

/** Inverse: convert a plate pixel (x, y) to geographic coordinates. */
fun platePxToLatLon(x: Float, y: Float, geoRef: PlateGeoRef): Pair<Double, Double> {
    val lat = geoRef.topLeftLat    - (y / geoRef.heightPx) * (geoRef.topLeftLat - geoRef.bottomRightLat)
    val lon = geoRef.topLeftLon    + (x / geoRef.widthPx)  * (geoRef.bottomRightLon - geoRef.topLeftLon)
    return Pair(lat, lon)
}
