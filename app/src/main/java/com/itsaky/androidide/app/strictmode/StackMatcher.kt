package com.itsaky.androidide.app.strictmode

/**
 * A matcher for matching stack frames.
 *
 * @author Akash Yadav
 */
sealed interface StackMatcher {
	/**
	 * Checks preconditions for the matcher.
	 */
	fun checkPreconditions()

	/**
	 * Whether the given [frames] match the matcher or not.
	 */
	fun matches(frames: List<StackFrame>): Boolean

	/**
	 * Match N frames adjacent and in order, anywhere in the stack.
	 *
	 * @property matchers The matchers to use.
	 */
	class Adjacent(
		private val matchers: List<FrameMatcher>,
	) : StackMatcher {
		override fun checkPreconditions() {
			check(matchers.size >= 2) {
				"Adjacent matcher requires at least 2 frame matchers"
			}
			check(matchers.distinctBy { System.identityHashCode(it) }.size == matchers.size) {
				"Adjacent matcher contains duplicate FrameMatcher instances"
			}
		}

		override fun matches(frames: List<StackFrame>): Boolean {
			if (frames.size < matchers.size) return false

			val maxStart = frames.size - matchers.size
			for (start in 0..maxStart) {
				var matched = true
				for (i in matchers.indices) {
					if (!matchers[i].matches(frames[start + i])) {
						matched = false
						break
					}
				}
				if (matched) return true
			}
			return false
		}
	}

	/**
	 * Match N frames in order, but not necessarily adjacent.
	 *
	 * @property matchers The matchers to use.
	 */
	class InOrder(
		private val matchers: List<FrameMatcher>,
	) : StackMatcher {
		override fun checkPreconditions() {
			check(matchers.size >= 2) {
				"InOrder matcher requires at least 2 frame matchers"
			}
			check(matchers.distinctBy { System.identityHashCode(it) }.size == matchers.size) {
				"InOrder matcher contains duplicate FrameMatcher instances"
			}
		}

		override fun matches(frames: List<StackFrame>): Boolean {
			var matcherIndex = 0

			for (frame in frames) {
				if (matchers[matcherIndex].matches(frame)) {
					matcherIndex++
					if (matcherIndex == matchers.size) {
						return true
					}
				}
			}
			return false
		}
	}

	/**
	 * Match multiple adjacent groups, each group in order, groups themselves in order.
	 *
	 * @property groups The groups to match.
	 */
	class AdjacentInOrder(
		private val groups: List<List<FrameMatcher>>,
	) : StackMatcher {
		override fun checkPreconditions() {
			check(groups.size >= 2) {
				"AdjacentInOrder matcher requires at least 2 groups"
			}
			check(groups.all { it.isNotEmpty() }) {
				"AdjacentInOrder matcher contains empty group"
			}
			check(groups.flatten().size >= 2) {
				"AdjacentInOrder matcher must contain at least 2 frame matchers total"
			}
			check(groups.any { it.size >= 2 }) {
				"AdjacentInOrder matcher must contain at least one adjacent group"
			}
		}

		override fun matches(frames: List<StackFrame>): Boolean {
			var frameIndex = 0

			for (group in groups) {
				var foundGroup = false

				val maxStart = frames.size - group.size
				while (frameIndex <= maxStart) {
					var matched = true
					for (i in group.indices) {
						if (!group[i].matches(frames[frameIndex + i])) {
							matched = false
							break
						}
					}

					if (matched) {
						frameIndex += group.size
						foundGroup = true
						break
					}

					frameIndex++
				}

				if (!foundGroup) return false
			}

			return true
		}
	}
}
