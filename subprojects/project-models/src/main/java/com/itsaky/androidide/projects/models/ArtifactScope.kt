package com.itsaky.androidide.projects.models

/**
 * A component of a module's build that compiles its own sources against its own dependencies: main
 * code, test fixtures, or one kind of host (JVM) or device test.
 *
 * Each scope has exactly one value, so it can key maps and caches. [key] is how the scope is stored
 * in the project model, by the tooling server that writes it and the IDE that reads it; [fromKey]
 * reads it back. Host and device test keys carry a prefix so one AGP test type name can never name
 * two scopes.
 */
sealed class ArtifactScope {
	/** The key this scope is stored under in the project model. */
	abstract val key: String

	/** The suffix of this scope's Kotlin analysis module path; empty for [Main]. */
	abstract val moduleSuffix: String

	/**
	 * Whether this scope's sources may reference [other]'s. Main sees only itself, test fixtures see
	 * main, and a test sees main and test fixtures; test scopes never see each other.
	 */
	fun sees(other: ArtifactScope): Boolean =
		when (this) {
			Main -> other == Main
			TestFixtures -> other == Main || other == TestFixtures
			is HostTest, is DeviceTest -> other == Main || other == TestFixtures || other == this
		}

	/** Main sources: `src/main` and the selected variant's layers over it. */
	data object Main : ArtifactScope() {
		override val key = "main"
		override val moduleSuffix = ""
	}

	/** Test fixtures: `src/testFixtures`, visible to the module's tests. */
	data object TestFixtures : ArtifactScope() {
		override val key = "testFixtures"
		override val moduleSuffix = "testFixtures"
	}

	/** Tests that run on the host JVM, named by the key AGP's model gives them (e.g. `_unit_test_`). */
	data class HostTest(
		val type: String,
	) : ArtifactScope() {
		override val key = "$HOST_PREFIX$type"
		override val moduleSuffix = camelCase(type)
	}

	/** Tests that run on a device, named by the key AGP's model gives them (e.g. `_android_test_`). */
	data class DeviceTest(
		val type: String,
	) : ArtifactScope() {
		override val key = "$DEVICE_PREFIX$type"
		override val moduleSuffix = camelCase(type)
	}

	companion object {
		private const val HOST_PREFIX = "host:"
		private const val DEVICE_PREFIX = "device:"

		// The type names are AGP's ComponentTypeImpl artifact names, the keys of its model's test maps.

		/** Unit tests, `src/test`. */
		@JvmField
		val UNIT_TEST = HostTest("_unit_test_")

		/** Screenshot tests, `src/screenshotTest`. */
		@JvmField
		val SCREENSHOT_TEST = HostTest("_screenshot_test_")

		/** Instrumented tests, `src/androidTest`. */
		@JvmField
		val ANDROID_TEST = DeviceTest("_android_test_")

		/**
		 * Main, test fixtures, unit tests, android tests, then other host and device tests by key: the
		 * order a file shared by several scopes is attributed in.
		 */
		@JvmField
		val ORDER: Comparator<ArtifactScope> =
			compareBy<ArtifactScope> { scope ->
				when (scope) {
					Main -> 0
					TestFixtures -> 1
					UNIT_TEST -> 2
					ANDROID_TEST -> 3
					is HostTest -> 4
					is DeviceTest -> 5
				}
			}.thenBy { it.key }

		/** `_unit_test_` -> `unitTest`, so Kotlin module paths stay readable. */
		private fun camelCase(type: String): String =
			type
				.split('_')
				.filter { it.isNotEmpty() }
				.mapIndexed { index, part ->
					if (index ==
						0
					) {
						part.replaceFirstChar(Char::lowercaseChar)
					} else {
						part.replaceFirstChar(Char::uppercaseChar)
					}
				}.joinToString("")

		/** The scope stored under [key], or null when [key] names no scope. */
		@JvmStatic
		fun fromKey(key: String): ArtifactScope? =
			when {
				key == Main.key -> Main
				key == TestFixtures.key -> TestFixtures
				key.startsWith(HOST_PREFIX) -> HostTest(key.removePrefix(HOST_PREFIX))
				key.startsWith(DEVICE_PREFIX) -> DeviceTest(key.removePrefix(DEVICE_PREFIX))
				else -> null
			}
	}
}
