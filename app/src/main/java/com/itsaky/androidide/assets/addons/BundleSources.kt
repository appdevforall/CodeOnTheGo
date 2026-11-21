package com.itsaky.androidide.assets.addons

import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** The bundle of a release build: `plugins/` and `templates/` in the APK assets. */
class AssetManagerBundleSource(
	private val assets: AssetManager,
) : BundleSource {
	override fun names(dir: String): List<String> = assets.list(dir)?.toList().orEmpty()

	override fun size(
		dir: String,
		name: String,
	): Long =
		try {
			assets.openFd("$dir/$name").use { it.length }
		} catch (e: IOException) {
			// openFd refuses an asset the APK stores compressed; count the bytes instead.
			assets.open("$dir/$name").use { input -> input.countBytes() }
		}

	override fun open(
		dir: String,
		name: String,
	): InputStream = assets.open("$dir/$name")
}

/** The bundle of a debug build: `plugins/<name>` and `templates/<name>` entries of the split assets zip. */
class ZipBundleSource(
	private val zip: ZipFile,
) : BundleSource {
	override fun names(dir: String): List<String> =
		zip
			.entries()
			.asSequence()
			.filter { !it.isDirectory && it.name.startsWith("$dir/") }
			.map { it.name.removePrefix("$dir/") }
			.filter { it.isNotEmpty() && '/' !in it }
			.toList()

	override fun size(
		dir: String,
		name: String,
	): Long = entry(dir, name).size

	override fun open(
		dir: String,
		name: String,
	): InputStream = zip.getInputStream(entry(dir, name))

	private fun entry(
		dir: String,
		name: String,
	) = zip.getEntry("$dir/$name") ?: throw FileNotFoundException("Entry '$dir/$name' not found in assets zip file")
}

private fun InputStream.countBytes(): Long {
	val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
	var total = 0L
	while (true) {
		val read = read(buffer)
		if (read < 0) return total
		total += read
	}
}
