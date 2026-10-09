package com.itsaky.androidide.lsp.kotlin.completion

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * [collectUpToLimit] is the cap that unimported-symbol completion runs on.
 *
 * The behaviour under test is that the cap counts items the user is offered, not rows the index
 * returned. Capping the fetch instead is what made a prefix whose leading rows are all rejected --
 * by the current-package, visibility or member-kind filters -- produce nothing while valid matches
 * sat just past the cap.
 */
@RunWith(JUnit4::class)
class CollectUpToLimitTest {
	private fun source(vararg items: String): () -> Sequence<String> = { items.asSequence() }

	@Test
	fun `rejected items do not consume the limit`() {
		val offered = (1..200).map { "reject$it" } + listOf("keepA", "keepB", "keepC")
		val kept = mutableListOf<String>()

		val accepted =
			collectUpToLimit(limit = 3, sources = listOf(source(*offered.toTypedArray()))) { item ->
				if (item.startsWith("keep")) {
					kept += item
					true
				} else {
					false
				}
			}

		assertThat(accepted).isEqualTo(3)
		assertThat(kept).containsExactly("keepA", "keepB", "keepC")
	}

	@Test
	fun `collection stops once the limit is reached`() {
		val kept = mutableListOf<String>()

		val accepted =
			collectUpToLimit(limit = 2, sources = listOf(source("a", "b", "c", "d"))) { item ->
				kept += item
				true
			}

		assertThat(accepted).isEqualTo(2)
		assertThat(kept).containsExactly("a", "b")
	}

	@Test
	fun `a later source is never queried once the limit is reached`() {
		var secondSourceQueried = false
		val second: () -> Sequence<String> = {
			secondSourceQueried = true
			sequenceOf("x")
		}

		collectUpToLimit(limit = 2, sources = listOf(source("a", "b"), second)) { true }

		// The supplier matters: an index query runs eagerly, so taking a sequence here would have
		// issued the query regardless of the limit.
		assertThat(secondSourceQueried).isFalse()
	}

	@Test
	fun `later sources fill the remainder when earlier ones fall short`() {
		val kept = mutableListOf<String>()

		val accepted =
			collectUpToLimit(
				limit = 4,
				sources = listOf(source("a"), source("b", "c"), source("d", "e")),
			) { item ->
				kept += item
				true
			}

		assertThat(accepted).isEqualTo(4)
		assertThat(kept).containsExactly("a", "b", "c", "d").inOrder()
	}

	@Test
	fun `every source is drained when the limit is never reached`() {
		val kept = mutableListOf<String>()

		val accepted =
			collectUpToLimit(limit = 100, sources = listOf(source("a"), source("b"))) { item ->
				kept += item
				true
			}

		assertThat(accepted).isEqualTo(2)
		assertThat(kept).containsExactly("a", "b").inOrder()
	}

	@Test
	fun `a source that yields nothing acceptable does not block the next one`() {
		val kept = mutableListOf<String>()

		collectUpToLimit(
			limit = 2,
			sources = listOf(source("reject1", "reject2"), source("keepA", "keepB")),
		) { item ->
			if (item.startsWith("keep")) {
				kept += item
				true
			} else {
				false
			}
		}

		assertThat(kept).containsExactly("keepA", "keepB")
	}
}
