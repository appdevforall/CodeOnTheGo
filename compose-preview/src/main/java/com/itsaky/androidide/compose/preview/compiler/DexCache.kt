package com.itsaky.androidide.compose.preview.compiler

import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

class DexCache(private val cacheDir: File) {

    init {
        cacheDir.mkdirs()
    }

    fun getCachedDex(sourceHash: String): CachedDexResult? {
        val cacheEntry = File(cacheDir, "$sourceHash.dex")
        val metaFile = File(cacheDir, "$sourceHash.meta")

        if (!cacheEntry.exists() || !metaFile.exists()) {
            return null
        }

        val meta = metaFile.readLines()
        if (meta.size < 2) {
            cacheEntry.delete()
            metaFile.delete()
            return null
        }

        LOG.debug("Cache hit for hash: {}", sourceHash)
        return CachedDexResult(
            dexFile = cacheEntry,
            className = meta[0],
            functionName = meta[1]
        )
    }

    fun cacheDex(
        sourceHash: String,
        dexFile: File,
        className: String,
        functionName: String
    ) {
        val cacheEntry = File(cacheDir, "$sourceHash.dex")
        val metaFile = File(cacheDir, "$sourceHash.meta")

        dexFile.copyTo(cacheEntry, overwrite = true)
        metaFile.writeText("$className\n$functionName")

        LOG.debug("Cached DEX for hash: {}", sourceHash)
        cleanOldEntries()
    }

    fun computeSourceHash(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun cleanOldEntries() {
        val entries = cacheDir.listFiles { file -> file.extension == "dex" } ?: return
        if (entries.size <= MAX_CACHE_ENTRIES) return

        entries
            .sortedBy { it.lastModified() }
            .take(entries.size - MAX_CACHE_ENTRIES)
            .forEach { entry ->
                entry.delete()
                File(entry.parent, "${entry.nameWithoutExtension}.meta").delete()
            }

        LOG.debug("Cleaned old cache entries, kept {}", MAX_CACHE_ENTRIES)
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        LOG.info("Cache cleared")
    }

    data class CachedDexResult(
        val dexFile: File,
        val className: String,
        val functionName: String
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(DexCache::class.java)
        private const val MAX_CACHE_ENTRIES = 20
    }
}
