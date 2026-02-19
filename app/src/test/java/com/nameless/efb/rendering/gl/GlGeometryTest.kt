package com.nameless.efb.rendering.gl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class GlGeometryTest {

    // ── buildCircleStrip ─────────────────────────────────────────────────────

    @Test
    fun `buildCircleStrip correctVertexCount`() {
        val segments = 64
        val verts = buildCircleStrip(segments)
        // center + (segments+1) perimeter vertices, each with x and y
        assertEquals((segments + 2) * 2, verts.size)
    }

    @Test
    fun `buildCircleStrip centerIsOrigin`() {
        val verts = buildCircleStrip(32)
        assertEquals(0f, verts[0], 1e-6f)
        assertEquals(0f, verts[1], 1e-6f)
    }

    @Test
    fun `buildCircleStrip closesAtStart`() {
        // The last perimeter vertex must equal the first perimeter vertex
        // so the triangle fan closes cleanly.
        val segments = 48
        val verts = buildCircleStrip(segments)
        // First perimeter vertex: index 2, 3
        val firstX = verts[2]
        val firstY = verts[3]
        // Last perimeter vertex: index (segments+1)*2, (segments+1)*2+1
        val lastX = verts[(segments + 1) * 2]
        val lastY = verts[(segments + 1) * 2 + 1]
        assertEquals(firstX, lastX, 1e-5f)
        assertEquals(firstY, lastY, 1e-5f)
    }

    @Test
    fun `buildCircleStrip perimeterVerticesOnUnitCircle`() {
        val verts = buildCircleStrip(64)
        // All perimeter vertices (skip center at index 0,1) should be at radius 1.0
        for (i in 1..(64 + 1)) {
            val x = verts[i * 2]
            val y = verts[i * 2 + 1]
            val r = sqrt((x * x + y * y).toDouble()).toFloat()
            assertEquals(1f, r, 1e-5f, "Vertex $i not on unit circle: r=$r")
        }
    }

    // ── buildArcStrip ────────────────────────────────────────────────────────

    @Test
    fun `buildArcStrip correctVertexCount`() {
        val segments = 32
        val verts = buildArcStrip(0.8f, 1.0f, 0f, 180f, segments)
        // (segments+1) pairs of (inner, outer) = (segments+1)*4 floats
        assertEquals((segments + 1) * 4, verts.size)
    }

    @Test
    fun `buildArcStrip innerRadiusLessThanOuter`() {
        val inner = 0.6f
        val outer = 1.0f
        val verts = buildArcStrip(inner, outer, 0f, 360f, 32)
        // At each step: innerX, innerY, outerX, outerY
        for (i in 0..32) {
            val ix = verts[i * 4]
            val iy = verts[i * 4 + 1]
            val ox = verts[i * 4 + 2]
            val oy = verts[i * 4 + 3]
            val ri = sqrt((ix * ix + iy * iy).toDouble()).toFloat()
            val ro = sqrt((ox * ox + oy * oy).toDouble()).toFloat()
            assertEquals(inner, ri, 1e-5f, "Inner radius mismatch at vertex $i")
            assertEquals(outer, ro, 1e-5f, "Outer radius mismatch at vertex $i")
        }
    }

    // ── buildQuad ────────────────────────────────────────────────────────────

    @Test
    fun `buildQuad hasFourVertices`() {
        val q = buildQuad()
        // 4 vertices × 4 components (x, y, u, v)
        assertEquals(16, q.size)
    }

    @Test
    fun `buildQuad uvCornersAreZeroToOne`() {
        val q = buildQuad()
        // Collect all (u,v) pairs from the four vertices
        val uvs = (0 until 4).map { i -> Pair(q[i * 4 + 2], q[i * 4 + 3]) }.toSet()
        assertTrue(uvs.contains(Pair(0f, 0f)), "Missing UV (0,0)")
        assertTrue(uvs.contains(Pair(1f, 0f)), "Missing UV (1,0)")
        assertTrue(uvs.contains(Pair(0f, 1f)), "Missing UV (0,1)")
        assertTrue(uvs.contains(Pair(1f, 1f)), "Missing UV (1,1)")
    }

    @Test
    fun `buildQuad positionsCenteredAtOrigin`() {
        val q = buildQuad()
        // All x values should be ±0.5
        val xs = (0 until 4).map { q[it * 4] }.toSet()
        assertEquals(setOf(-0.5f, 0.5f), xs)
        // All y values should be ±0.5
        val ys = (0 until 4).map { q[it * 4 + 1] }.toSet()
        assertEquals(setOf(-0.5f, 0.5f), ys)
    }

    // ── FontAtlas UV math (pure, no Android API) ─────────────────────────────

    @Test
    fun `fontAtlas uvMap coversAllAsciiPrintableChars`() {
        // Verify the math: 95 printable ASCII chars (32–126) fit in a
        // square grid with ceil(sqrt(95)) = 10 columns
        val charCount = (32..126).count()
        assertEquals(95, charCount)
        val cols = Math.ceil(sqrt(charCount.toDouble())).toInt()
        assertEquals(10, cols)
        val rows = Math.ceil(charCount.toDouble() / cols).toInt()
        assertEquals(10, rows)
        // Every char should map to a distinct (col, row) cell
        val cells = (32..126).map { code ->
            val idx = code - 32
            Pair(idx % cols, idx / cols)
        }
        assertEquals(charCount, cells.distinct().size)
    }

    @Test
    fun `fontAtlas uvMap cellsWithinAtlasBounds`() {
        val atlasSize = 512
        val glyphSize = 48
        val chars = 95
        val cols = Math.ceil(sqrt(chars.toDouble())).toInt() // 10
        (0 until chars).forEach { idx ->
            val col = idx % cols
            val row = idx / cols
            val u0 = col * glyphSize
            val v0 = row * glyphSize
            val u1 = u0 + glyphSize
            val v1 = v0 + glyphSize
            assertTrue(u1 <= atlasSize, "Char $idx: u1=$u1 exceeds atlas width")
            assertTrue(v1 <= atlasSize, "Char $idx: v1=$v1 exceeds atlas height")
        }
    }
}
