package android.content.res

import java.io.File
import java.io.InputStream

/**
 * Shim AssetManager that reads from the app's assets directory on disk.
 *
 * Used by ShaderManager (loads .vert/.frag files) and GaugeTextureAtlas.
 *
 * @param assetRoot  Path to `app/src/main/assets/`
 */
class AssetManager(private val assetRoot: File) {
    fun open(fileName: String): InputStream {
        val file = File(assetRoot, fileName)
        require(file.exists()) { "Asset not found: $fileName (looked in ${file.absolutePath})" }
        return file.inputStream()
    }
}
