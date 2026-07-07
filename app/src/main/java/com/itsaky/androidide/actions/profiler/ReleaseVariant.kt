package com.itsaky.androidide.actions.profiler

/**
 * Maps [selectedVariant] to the name of its release counterpart, or `null` when there is none.
 *
 * Variant names are `<flavors><BuildType>` (build type capitalized), e.g. `debug`, `freeDebug`.
 * `debug` -> `release`, `freeDebug` -> `freeRelease`. An already-release variant is returned as-is.
 * A custom build type (neither debug nor release) yields `null`.
 *
 * The computed name is only returned if it is present in [allVariants]; otherwise `null`.
 */
internal fun releaseVariantName(
	selectedVariant: String,
	allVariants: List<String>,
): String? {
	val candidate =
		when {
			selectedVariant == "release" || selectedVariant.endsWith("Release") -> selectedVariant
			selectedVariant == "debug" -> "release"
			selectedVariant.endsWith("Debug") -> selectedVariant.removeSuffix("Debug") + "Release"
			else -> return null
		}
	return candidate.takeIf { it in allVariants }
}
