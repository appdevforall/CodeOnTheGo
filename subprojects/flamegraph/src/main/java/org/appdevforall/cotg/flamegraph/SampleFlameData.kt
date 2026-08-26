package org.appdevforall.cotg.flamegraph

import org.appdevforall.cotg.flamegraph.model.FlameNode
import org.appdevforall.cotg.flamegraph.model.parseFoldedStacks

/** Sample trees used by `@Preview`s. Internal so they don't leak into the public API. */
internal object SampleFlameData {
	/** A small, realistic CPU-style tree built from folded stacks. */
	val cpu: FlameNode<Nothing> =
		parseFoldedStacks(
			"""
			main;runLoop;handleMessage;doFrame;measure 40
			main;runLoop;handleMessage;doFrame;layout 25
			main;runLoop;handleMessage;doFrame;draw 60
			main;runLoop;handleMessage;input;dispatch 18
			main;runLoop;idle 12
			main;init;loadClasses 30
			main;init;inflate 22
			worker;decodeBitmap 35
			worker;gc 15
			""".trimIndent(),
		)

	/** Deep linear chain — exercises vertical scrolling. */
	val deep: FlameNode<Nothing> =
		run {
			var node = FlameNode<Nothing>(id = "leaf", label = "frame59", value = 1.0)
			for (i in 58 downTo 0) {
				node = FlameNode(id = "d$i", label = "frame$i", value = 1.0, children = listOf(node))
			}
			node
		}

	/** Many narrow children — exercises sub-pixel culling and label thresholds. */
	val wide: FlameNode<Nothing> =
		run {
			val children =
				(0 until 120).map { i ->
					FlameNode<Nothing>(id = "w$i", label = "fn$i", value = (1 + i % 7).toDouble())
				}
			FlameNode(id = "0", label = "root", value = children.sumOf { it.value }, children = children)
		}

	val single: FlameNode<Nothing> = FlameNode(id = "0", label = "only", value = 1.0)

	val empty: FlameNode<Nothing> = FlameNode(id = "0", label = "root", value = 0.0)
}
