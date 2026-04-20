package com.itsaky.androidide.plugins.manager.documentation

import android.content.res.AssetManager
import java.io.IOException

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
                assets.open(absolute).use { it.readBytes() }
            } catch (_: IOException) {
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
}
