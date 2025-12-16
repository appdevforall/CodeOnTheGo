package com.itsaky.androidide.app.strictmode

/**
 * Matcher for a single stack frame.
 */
fun interface FrameMatcher {

	/**
	 * Whether the given [frame] matches the matcher or not.
	 *
	 * @param frame The frame to match.
	 * @return Whether the given [frame] matches the matcher or not.
	 */
	fun matches(frame: StackFrame): Boolean

	companion object {

		/* ---------- Class matchers ---------- */

		fun classEquals(name: String): FrameMatcher =
			FrameMatcher { it.className == name }

		fun classStartsWith(prefix: String): FrameMatcher =
			FrameMatcher { it.className.startsWith(prefix) }

		fun classContains(token: String): FrameMatcher =
			FrameMatcher { it.className.contains(token) }

		/* ---------- Method matchers ---------- */

		fun methodEquals(name: String): FrameMatcher =
			FrameMatcher { it.methodName == name }

		fun methodStartsWith(prefix: String): FrameMatcher =
			FrameMatcher { it.methodName.startsWith(prefix) }

		fun methodContains(token: String): FrameMatcher =
			FrameMatcher { it.methodName.contains(token) }

		/* ---------- Combined matchers ---------- */

		fun classAndMethod(
			className: String,
			methodName: String
		): FrameMatcher =
			FrameMatcher {
				it.className == className &&
						it.methodName == methodName
			}

		fun classStartsWithAndMethod(
			classPrefix: String,
			methodName: String
		): FrameMatcher =
			FrameMatcher {
				it.className.startsWith(classPrefix) &&
						it.methodName == methodName
			}

		/* ---------- Composition helpers ---------- */

		fun anyOf(vararg matchers: FrameMatcher): FrameMatcher =
			FrameMatcher { frame ->
				matchers.any { it.matches(frame) }
			}

		fun allOf(vararg matchers: FrameMatcher): FrameMatcher =
			FrameMatcher { frame ->
				matchers.all { it.matches(frame) }
			}
	}
}
