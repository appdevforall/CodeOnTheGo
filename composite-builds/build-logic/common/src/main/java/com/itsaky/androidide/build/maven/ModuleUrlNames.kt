package com.itsaky.androidide.build.maven

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Stores each file of a local Maven repository under the name a repository serves it at.
 *
 * Gradle's module cache keeps a file under the `name` its `.module` metadata gives it, but a build
 * asks the repository for the `url`. For androidx.lifecycle:lifecycle-viewmodel-android:2.9.4 the two
 * differ (lifecycle-viewmodel-release.aar against lifecycle-viewmodel-android-2.9.4.aar), and an
 * offline build fails until the file carries its url name (ADFA-7220). dev-assets'
 * scripts/cache-to-local-maven-repo.py does the same; keep the two in step.
 *
 * A url with a slash points outside the module's directory and is left alone, as is a file that is
 * missing or whose url name already exists. The name copy is removed unless another variant serves
 * the file under that name.
 *
 * @return one line per file copied or removed, for the log
 */
fun renameFilesToModuleUrls(repo: Path): List<String> {
	val log = mutableListOf<String>()
	val modules =
		Files.walk(repo).use { paths ->
			paths.filter { it.isRegularFile() && it.name.endsWith(".module") }.sorted().toList()
		}
	for (module in modules) {
		val metadata =
			try {
				JsonSlurper().parse(module.toFile())
			} catch (e: Exception) {
				throw IllegalStateException("Cannot read $module: ${e.message}", e)
			}
		val variants = ((metadata as? Map<*, *>)?.get("variants") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
		val files = variants.flatMap { (it["files"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>() }
		val urls = files.mapNotNull { it["url"] as? String }.toSet()
		val copiedNames = sortedSetOf<String>()
		for (file in files) {
			val name = file["name"] as? String ?: continue
			val url = file["url"] as? String ?: continue
			if (name == url || '/' in name || '/' in url) continue
			val src = module.resolveSibling(name)
			val dst = module.resolveSibling(url)
			if (!src.isRegularFile() || Files.exists(dst)) continue
			Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES)
			copiedNames += name
			log += "Copied ${repo.relativize(src)} -> $url"
		}
		for (name in copiedNames - urls) {
			val src = module.resolveSibling(name)
			Files.delete(src)
			log += "Removed ${repo.relativize(src)}"
		}
	}
	return log
}
