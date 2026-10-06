package com.itsaky.androidide.quickbuild

/**
 * Composes the Gradle task path for the quick-build proxy app build's `assemble<Variant>` task from
 * a module's Gradle project path and the variant CoGo has selected.
 *
 * The variant is part of the task name, not just a detail: the flavor-agnostic `assembleDebug`
 * LIFECYCLE task runs EVERY flavor's debug variant, so a flavored project builds and reports more
 * than one app. And a root/single-module project's path is `:`, so naive `"$modulePath:assemble"`
 * composition yields `::assembleDebug`, which Gradle's task selector rejects outright.
 */
object QuickBuildTaskPaths {
	/** AGP's own name for a variant with no flavors and the default debug build type. */
	const val DEFAULT_VARIANT = "debug"

	/**
	 * The `assemble<Variant>` task path for a module.
	 *
	 * @param modulePath the module's Gradle path; `:` or blank means the root project.
	 * @param variantName the variant to build; blank falls back to [DEFAULT_VARIANT].
	 * @return the fully qualified task path.
	 */
	fun assembleVariant(
		modulePath: String,
		variantName: String = DEFAULT_VARIANT,
	): String {
		val variant = variantName.ifBlank { DEFAULT_VARIANT }
		// AGP names the task "assemble" + the variant name with its first letter uppercased
		// ("demoDebug" -> "assembleDemoDebug"); the rest of the camel case is kept as-is.
		val task = "assemble" + variant.replaceFirstChar { it.uppercaseChar() }
		return if (modulePath == ":" || modulePath.isBlank()) {
			":$task"
		} else {
			"$modulePath:$task"
		}
	}

	/**
	 * Where the Gradle plugin writes that variant's proxy app report, relative to the
	 * directory owning the `build/` dir - the other half of the same contract, kept next to
	 * the task name so the two cannot drift apart. Variant-scoped like every other Quick
	 * Build output: a flavored project has one report per debuggable variant.
	 */
	fun setupJson(variantName: String = DEFAULT_VARIANT): String = "build/quickbuild/${variantName.ifBlank { DEFAULT_VARIANT }}/setup.json"
}
