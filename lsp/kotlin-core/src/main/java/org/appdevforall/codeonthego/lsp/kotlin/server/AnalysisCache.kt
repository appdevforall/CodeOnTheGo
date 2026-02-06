package org.appdevforall.codeonthego.lsp.kotlin.server

import org.appdevforall.codeonthego.lsp.kotlin.parser.ParseResult
import org.appdevforall.codeonthego.lsp.kotlin.semantic.AnalysisContext
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class AnalysisCache(
    private val maxCacheSize: Int = 100
) {
    private val parseCache = ConcurrentHashMap<String, CachedParseResult>()
    private val symbolTableCache = ConcurrentHashMap<String, CachedSymbolTable>()
    private val analysisCache = ConcurrentHashMap<String, CachedAnalysis>()

    fun getCachedParse(uri: String, content: String): ParseResult? {
        val hash = computeHash(content)
        val cached = parseCache[uri] ?: return null
        return if (cached.contentHash == hash) cached.result else null
    }

    fun cacheParse(uri: String, content: String, result: ParseResult) {
        evictIfNeeded(parseCache)
        parseCache[uri] = CachedParseResult(
            contentHash = computeHash(content),
            result = result,
            timestamp = System.currentTimeMillis()
        )
    }

    fun getCachedSymbolTable(uri: String, content: String): SymbolTable? {
        val hash = computeHash(content)
        val cached = symbolTableCache[uri] ?: return null
        return if (cached.contentHash == hash) cached.symbolTable else null
    }

    fun cacheSymbolTable(uri: String, content: String, symbolTable: SymbolTable) {
        evictIfNeeded(symbolTableCache)
        symbolTableCache[uri] = CachedSymbolTable(
            contentHash = computeHash(content),
            symbolTable = symbolTable,
            timestamp = System.currentTimeMillis()
        )
    }

    fun getCachedAnalysis(uri: String, content: String): AnalysisContext? {
        val hash = computeHash(content)
        val cached = analysisCache[uri] ?: return null
        return if (cached.contentHash == hash) cached.context else null
    }

    fun cacheAnalysis(uri: String, content: String, context: AnalysisContext) {
        evictIfNeeded(analysisCache)
        analysisCache[uri] = CachedAnalysis(
            contentHash = computeHash(content),
            context = context,
            timestamp = System.currentTimeMillis()
        )
    }

    fun invalidate(uri: String) {
        parseCache.remove(uri)
        symbolTableCache.remove(uri)
        analysisCache.remove(uri)
    }

    fun invalidateAll() {
        parseCache.clear()
        symbolTableCache.clear()
        analysisCache.clear()
    }

    fun cacheStats(): CacheStats {
        return CacheStats(
            parseEntries = parseCache.size,
            symbolTableEntries = symbolTableCache.size,
            analysisEntries = analysisCache.size
        )
    }

    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun <V : Timestamped> evictIfNeeded(cache: ConcurrentHashMap<String, V>) {
        if (cache.size >= maxCacheSize) {
            val oldestEntry = cache.entries
                .minByOrNull { it.value.timestamp }
                ?.key
            oldestEntry?.let { cache.remove(it) }
        }
    }
}

interface Timestamped {
    val timestamp: Long
}

data class CachedParseResult(
    val contentHash: String,
    val result: ParseResult,
    override val timestamp: Long
) : Timestamped

data class CachedSymbolTable(
    val contentHash: String,
    val symbolTable: SymbolTable,
    override val timestamp: Long
) : Timestamped

data class CachedAnalysis(
    val contentHash: String,
    val context: AnalysisContext,
    override val timestamp: Long
) : Timestamped

data class CacheStats(
    val parseEntries: Int,
    val symbolTableEntries: Int,
    val analysisEntries: Int
)
