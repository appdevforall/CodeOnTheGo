/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.editor.ui

import android.util.LruCache
import com.itsaky.androidide.models.Position
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory

/**
 * Provides inline code suggestions from local LLM.
 *
 * Handles request management, caching, and cancellation.
 */
class SuggestionProvider(
    private val editor: IDEEditor,
    private val cache: SuggestionCache = DefaultSuggestionCache()
) {

    private val log = LoggerFactory.getLogger(SuggestionProvider::class.java)

    private var activeRequest: Job? = null

    /**
     * Request a code suggestion for the given context.
     *
     * @return SuggestionData if available, null if no suggestion or error
     */
    suspend fun requestSuggestion(
        cursorPosition: Position,
        fileContent: String,
        language: String
    ): SuggestionData? {
        // Cancel any in-flight request
        cancelActiveRequest()

        // Check cache first
        val cacheKey = computeCacheKey(cursorPosition, fileContent, language)
        cache.get(cacheKey)?.let { cached ->
            val age = System.currentTimeMillis() - cached.requestTimestamp
            if (age < 30_000) {  // Global constraint: 30 second expiry
                log.debug("Cache hit for suggestion")
                return cached
            } else {
                cache.remove(cacheKey)
            }
        }

        // TODO: Integrate with LocalLlmRepositoryImpl in next task
        // For now, return null (no suggestion)
        log.debug("No suggestion available (LLM not integrated yet)")
        return null
    }

    /**
     * Cancel the currently active suggestion request.
     */
    fun cancelActiveRequest() {
        activeRequest?.cancel()
        activeRequest = null
    }

    /**
     * Clear the suggestion cache.
     */
    fun clearCache() {
        cache.clear()
        log.debug("Suggestion cache cleared")
    }

    private fun computeCacheKey(
        position: Position,
        fileContent: String,
        language: String
    ): String {
        // Simple cache key: combine position and surrounding context
        val contextWindow = fileContent.take(500)  // First 500 chars
        return "${position.line}:${position.column}:${language}:${contextWindow.hashCode()}"
    }
}

/**
 * Interface for suggestion caching to support testing.
 */
interface SuggestionCache {
    fun get(key: String): SuggestionData?
    fun put(key: String, value: SuggestionData)
    fun remove(key: String)
    fun clear()
}

/**
 * Default implementation using Android's LruCache.
 * Cache size: 20 items (global constraint)
 */
class DefaultSuggestionCache : SuggestionCache {

    private val lruCache = LruCache<String, SuggestionData>(20)

    override fun get(key: String): SuggestionData? = lruCache.get(key)

    override fun put(key: String, value: SuggestionData) {
        lruCache.put(key, value)
    }

    override fun remove(key: String) {
        lruCache.remove(key)
    }

    override fun clear() {
        lruCache.evictAll()
    }
}
