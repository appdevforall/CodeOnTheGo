package com.itsaky.androidide.lsp.kotlin.completion

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.models.MatchLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MatchLevelMemoTest {

	@Test
	fun `includes fuzzy non-prefix matches`() {
		// "toStirng" is a transposition of "toString" — not a prefix, but a
		// high fuzzy ratio (> 59), so Java admits it and K2 must too.
		val level = memoizedMatchLevel(HashMap(), name = "toString", partial = "toStirng")
		assertThat(level).isEqualTo(MatchLevel.PARTIAL_MATCH)
	}

	@Test
	fun `keeps prefix matches in their tier`() {
		val level = memoizedMatchLevel(HashMap(), name = "toString", partial = "toS")
		assertThat(level).isEqualTo(MatchLevel.CASE_SENSITIVE_PREFIX)
	}

	@Test
	fun `excludes candidates below the fuzzy threshold`() {
		val level = memoizedMatchLevel(HashMap(), name = "toString", partial = "xyzw")
		assertThat(level).isEqualTo(MatchLevel.NO_MATCH)
	}

	@Test
	fun `computes match level once per name and reuses the cache`() {
		// Pre-seed a deliberately wrong value. A recompute would return
		// CASE_SENSITIVE_EQUAL; getting NO_MATCH back proves the cache was reused.
		val cache = HashMap<String, MatchLevel>().apply { put("toString", MatchLevel.NO_MATCH) }
		val level = memoizedMatchLevel(cache, name = "toString", partial = "toString")
		assertThat(level).isEqualTo(MatchLevel.NO_MATCH)
		assertThat(cache).hasSize(1)
	}
}
