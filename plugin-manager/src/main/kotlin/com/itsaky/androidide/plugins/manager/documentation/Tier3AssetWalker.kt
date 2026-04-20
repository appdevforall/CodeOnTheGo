package com.itsaky.androidide.plugins.manager.documentation

import android.content.res.AssetManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal data class Tier3Asset(
    val relativePath: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Tier3Asset

        if (relativePath != other.relativePath) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = relativePath.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

internal object Tier3AssetWalker {

    private const val TAG = "Tier3AssetWalker"
    private const val MAX_ASSET_BYTES = 10L * 1024L * 1024L

    fun walk(assets: AssetManager, rootAssetPath: String): Sequence<Tier3Asset> = sequence {
        val root = rootAssetPath.trim('/')
        yieldAll(walkDir(assets, root, relative = ""))
    }

    private fun walkDir(
        assets: AssetManager,
        absolute: String,
        relative: String
    ): Sequence<Tier3Asset> = sequence {
        val children = assets.list(absolute) ?: emptyArray()
        if (children.isEmpty()) {
            val bytes = try {
                assets.open(absolute).use { readBounded(it, MAX_ASSET_BYTES) }
            } catch (_: IOException) {
                return@sequence
            }
            if (bytes == null) {
                Log.w(TAG, "Skipping Tier 3 asset '$absolute' — exceeds $MAX_ASSET_BYTES byte limit")
                return@sequence
            }
            if (relative.isNotEmpty()) {
                yield(Tier3Asset(relative, bytes))
            }
            return@sequence
        }
        for (child in children) {
            val childAbs = if (absolute.isEmpty()) child else "$absolute/$child"
            val childRel = if (relative.isEmpty()) child else "$relative/$child"
            yieldAll(walkDir(assets, childAbs, childRel))
        }
    }

    private fun readBounded(stream: InputStream, limit: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val tmp = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(tmp)
            if (n < 0) break
            total += n
            if (total > limit) return null
            buffer.write(tmp, 0, n)
        }
        return buffer.toByteArray()
    }
}
