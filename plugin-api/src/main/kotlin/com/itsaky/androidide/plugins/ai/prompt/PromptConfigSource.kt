package com.itsaky.androidide.plugins.ai.prompt

import android.content.res.AssetManager

/**
 * Where prompt config files are read from: the plugin's assets on device, a directory in tests.
 * Paths are relative to the source's root; the call blocks, so [PromptConfigLoader] makes it off
 * the main thread.
 */
fun interface PromptConfigSource {
	/**
	 * Reads one file.
	 *
	 * @param path the file, relative to the root.
	 * @return the file's text, decoded as UTF-8.
	 * @throws java.io.FileNotFoundException when there is no such file.
	 */
	fun read(path: String): String
}

/**
 * Reads config from a directory of the plugin's own assets.
 * The [AssetManager] must be the plugin's, not the host's: each plugin sees only its own assets.
 *
 * @param assets the plugin's asset manager.
 * @param root the assets subdirectory holding the config.
 */
class AssetPromptConfigSource
	@JvmOverloads
	constructor(
		private val assets: AssetManager,
		private val root: String = DEFAULT_ROOT,
	) : PromptConfigSource {
		override fun read(path: String): String = assets.open("$root/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }

		companion object {
			/** The assets subdirectory the prompt config ships in. */
			const val DEFAULT_ROOT = "prompts"
		}
	}
