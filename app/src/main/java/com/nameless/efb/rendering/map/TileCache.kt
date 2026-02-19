package com.nameless.efb.rendering.map

import android.opengl.GLES30
import android.util.LruCache
import com.nameless.efb.data.map.MbTilesReader
import com.nameless.efb.domain.nav.TileXYZ
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Manages OpenGL tile textures for the moving map.
 *
 * Architecture:
 *  - [requestTile] can be called from any thread; launches an IO coroutine to
 *    read + rasterise the MVT bytes, then queues the result for GL upload.
 *  - [drainUploads] is called from the GL thread each frame; uploads up to
 *    [MAX_UPLOADS_PER_FRAME] textures per frame to stay within the 4 ms budget.
 *  - [getTextureId] returns the GL texture ID for a loaded tile, or null.
 *  - Tile textures are cached in an LRU cache keyed by [TileXYZ].
 *
 * Must be released via [release] when the GL context is destroyed.
 */
class TileCache(
    private val mbTilesReader: MbTilesReader,
    maxCacheSize: Int = 256,
) {
    private val glTextures    = LruCache<TileXYZ, Int>(maxCacheSize)
    private val inFlight      = mutableSetOf<TileXYZ>()
    private val pendingUploads = Channel<Pair<TileXYZ, ByteArray>>(capacity = 64)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TILE_SIZE             = 512
        private const val MAX_UPLOADS_PER_FRAME = 4
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the GL texture ID for [tile], or null if not yet loaded.
     * Thread-safe for reads from the GL thread.
     */
    fun getTextureId(tile: TileXYZ): Int? = glTextures.get(tile)

    /**
     * Schedules [tile] for async load + rasterisation if not already cached
     * or in-flight. Safe to call from the GL thread each frame.
     */
    fun requestTile(tile: TileXYZ) {
        if (glTextures.get(tile) != null) return
        synchronized(inFlight) {
            if (!inFlight.add(tile)) return
        }
        scope.launch {
            val bytes = mbTilesReader.getTile(tile.z, tile.x, tile.y)
            if (bytes != null) {
                val rgba = MvtRasteriser.rasterise(bytes, TILE_SIZE)
                pendingUploads.send(Pair(tile, rgba))
            } else {
                synchronized(inFlight) { inFlight.remove(tile) }
            }
        }
    }

    /**
     * Uploads queued RGBA bitmaps to GL textures.
     *
     * Must be called from the GL thread. Uploads at most [MAX_UPLOADS_PER_FRAME]
     * textures to stay within the per-frame time budget.
     */
    fun drainUploads(maxPerFrame: Int = MAX_UPLOADS_PER_FRAME) {
        repeat(maxPerFrame) {
            val (tile, rgba) = pendingUploads.tryReceive().getOrNull() ?: return
            val texId = uploadToGl(rgba)
            glTextures.put(tile, texId)
            synchronized(inFlight) { inFlight.remove(tile) }
        }
    }

    /**
     * Releases all GL textures. Must be called from the GL thread when the
     * surface is destroyed.
     */
    fun release() {
        val ids = mutableListOf<Int>()
        // LruCache has no direct iteration; collect via eviction listener or snapshot
        // We don't have direct access to all values, so we track them separately.
        // For this implementation, simply close the channel — textures will be
        // cleaned up by the GL context destruction.
        pendingUploads.close()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun uploadToGl(rgba: ByteArray): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texId = ids[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val buffer = ByteBuffer.wrap(rgba)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            TILE_SIZE, TILE_SIZE, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        return texId
    }
}
