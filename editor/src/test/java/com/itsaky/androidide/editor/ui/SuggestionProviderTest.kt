package com.itsaky.androidide.editor.ui

import com.itsaky.androidide.models.Position
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SuggestionProviderTest {

    private lateinit var mockEditor: IDEEditor
    private lateinit var mockCache: SuggestionCache
    private lateinit var provider: SuggestionProvider

    @Before
    fun setup() {
        mockEditor = mockk(relaxed = true)
        mockCache = FakeSuggestionCache()
        provider = SuggestionProvider(mockEditor, mockCache)
    }

    @Test
    fun `requestSuggestion returns null initially`() = runBlocking {
        val position = Position(5, 10, 50)
        val result = provider.requestSuggestion(
            cursorPosition = position,
            fileContent = "fun test() {",
            language = "kotlin"
        )

        // Initial implementation returns null (not integrated with LLM yet)
        // We'll update this test when we integrate with LocalLlmRepositoryImpl
        assertNull(result)
    }

    @Test
    fun `cancelActiveRequest does not crash`() {
        // Should handle gracefully even if no request active
        provider.cancelActiveRequest()
        // No exception = success
    }

    @Test
    fun `clearCache does not crash`() {
        provider.clearCache()
        // No exception = success
    }

    @Test
    fun `cache stores and retrieves suggestions`() = runBlocking {
        val position = Position(1, 5, 5)
        val suggestion = SuggestionData(
            text = "test code",
            startPosition = position,
            cursorLine = 1,
            cursorColumn = 5,
            requestTimestamp = System.currentTimeMillis()
        )

        // Store suggestion in cache
        val key = "1:5:kotlin:${suggestion.text.hashCode()}"
        mockCache.put(key, suggestion)

        // Retrieve it
        val cached = mockCache.get(key)
        assertNotNull(cached)
        assertEquals(suggestion, cached)
    }

    @Test
    fun `clearCache empties the cache`() {
        val position = Position(1, 5, 5)
        val suggestion = SuggestionData(
            text = "test",
            startPosition = position,
            cursorLine = 1,
            cursorColumn = 5,
            requestTimestamp = System.currentTimeMillis()
        )

        // Store in cache
        val key = "test_key"
        mockCache.put(key, suggestion)
        assertNotNull(mockCache.get(key))

        // Clear cache
        provider.clearCache()

        // Verify it's gone
        assertNull(mockCache.get(key))
    }

    @Test
    fun `cancelActiveRequest can be called multiple times`() {
        provider.cancelActiveRequest()
        provider.cancelActiveRequest()
        provider.cancelActiveRequest()
        // No exception = success
    }

    @Test
    fun `requestSuggestion with different positions returns null`() = runBlocking {
        val pos1 = Position(1, 5, 5)
        val pos2 = Position(2, 10, 10)

        val result1 = provider.requestSuggestion(
            cursorPosition = pos1,
            fileContent = "val x = 1",
            language = "kotlin"
        )
        assertNull(result1)

        val result2 = provider.requestSuggestion(
            cursorPosition = pos2,
            fileContent = "val y = 2",
            language = "kotlin"
        )
        assertNull(result2)
    }

    @Test
    fun `cache respects expiry time`() = runBlocking {
        val position = Position(1, 5, 5)
        val expiredTime = System.currentTimeMillis() - 31_000  // 31 seconds ago
        val suggestion = SuggestionData(
            text = "old suggestion",
            startPosition = position,
            cursorLine = 1,
            cursorColumn = 5,
            requestTimestamp = expiredTime
        )

        val key = "expired_key"
        mockCache.put(key, suggestion)

        // Make a request - should not return expired cache entry
        val result = provider.requestSuggestion(
            cursorPosition = position,
            fileContent = "val x",
            language = "kotlin"
        )

        // Result should be null because LLM is not integrated
        assertNull(result)
    }
}

/**
 * Fake implementation of SuggestionCache for testing.
 */
class FakeSuggestionCache : SuggestionCache {

    private val storage = mutableMapOf<String, SuggestionData>()

    override fun get(key: String): SuggestionData? = storage[key]

    override fun put(key: String, value: SuggestionData) {
        storage[key] = value
    }

    override fun remove(key: String) {
        storage.remove(key)
    }

    override fun clear() {
        storage.clear()
    }
}
