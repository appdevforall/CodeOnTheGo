package com.itsaky.androidide.gradle.quickbuild

import java.io.File

/**
 * The baseline-generation stamp asset: how the `-P` property value parses into a generation and
 * where the stamp lives relative to the generated assets root.
 *
 * The runtime's PayloadStore reads it pre-Context through the APK classloader as
 * `assets/quickbuild/baseline-generation.txt`, a sibling of the baseline payload dex
 * (`quickbuild/gen-0.dex`), and boots the baseline at the stamped generation.
 */
object BaselineGenerationAsset {
	/** Relative to the generated assets root; sibling of `quickbuild/gen-0.dex`. */
	const val ASSET_RELATIVE_PATH = "quickbuild/baseline-generation.txt"

	/**
	 * Parses the `-P` property value into a generation.
	 *
	 * Missing and malformed values both stamp 0, for compatibility: a host older than the
	 * stamping change passes no property, and the runtime treats a 0 stamp exactly like its
	 * pre-stamp constant baseline. Negative values count as malformed - the host's counter only
	 * hands out positive numbers.
	 *
	 * @param value the raw property value, or null when the property is unset
	 * @return the generation to stamp; 0 for missing, non-numeric, or negative input
	 */
	fun parse(value: Any?): Long {
		val parsed = value?.toString()?.trim()?.toLongOrNull() ?: return 0L
		return if (parsed < 0) 0L else parsed
	}

	/**
	 * Writes the stamp under [assetsRoot] at [ASSET_RELATIVE_PATH], as decimal text.
	 *
	 * @param assetsRoot the generated assets root AGP merges into the APK's `assets/`
	 * @param generation the generation to stamp
	 */
	fun write(
		assetsRoot: File,
		generation: Long,
	) {
		File(assetsRoot, ASSET_RELATIVE_PATH)
			.apply { parentFile.mkdirs() }
			.writeText(generation.toString())
	}
}
