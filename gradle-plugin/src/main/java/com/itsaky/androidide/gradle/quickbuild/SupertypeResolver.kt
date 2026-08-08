package com.itsaky.androidide.gradle.quickbuild

import org.objectweb.asm.ClassReader
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Builds the project-compiled supertype graph the restart closure needs, by reading class
 * headers from the diverted project classes.
 *
 * Interfaces count as supertypes, not just superclasses: a project interface with default
 * method bodies is component code, and DeployPolicy's live index counts interface edges too.
 * Dropping them makes an edit to such an interface a restart-policy false negative until the
 * component class itself recompiles in-session.
 */
internal object SupertypeResolver {
	/**
	 * Maps each project class to its direct supertypes (superclass first, then interfaces).
	 *
	 * @param payloadClassesRoot the divert task's output: `dirs/N/...` trees plus `jars/N.jar`
	 * @return every class found, including library supertypes; [chainFor] filters to the
	 *   project-compiled subset. Unreadable entries are skipped - a missing edge degrades to
	 *   "restart decides without that supertype", never a crash.
	 */
	fun supertypeIndex(payloadClassesRoot: File): Map<String, List<String>> {
		val index = mutableMapOf<String, List<String>>()

		File(payloadClassesRoot, "dirs")
			.walkTopDown()
			.filter { it.isFile && it.extension == "class" }
			.forEach { file ->
				runCatching { readHeader(file.readBytes()) }.getOrNull()?.let { (name, supertypes) ->
					index[name] = supertypes
				}
			}

		File(payloadClassesRoot, "jars")
			.listFiles { file -> file.extension == "jar" }
			.orEmpty()
			.forEach { jar ->
				try {
					JarFile(jar).use { jf ->
						jf
							.entries()
							.asSequence()
							.filter { !it.isDirectory && it.name.endsWith(".class") }
							.forEach { entry ->
								runCatching {
									readHeader(jf.getInputStream(entry).use { it.readBytes() })
								}.getOrNull()?.let { (name, supertypes) -> index[name] = supertypes }
							}
					}
				} catch (_: IOException) {
					// Corrupt jar: skip; the payload dex task fails the build on real corruption.
				}
			}

		return index
	}

	/**
	 * Walks the transitive supertypes of [className] that the project itself compiled.
	 *
	 * A supertype absent from [index] is framework or library code: it lives in the base APK
	 * and never hot-swaps, so the walk stops there.
	 *
	 * @param className the binary name (dots, not slashes) to start from; it is never included in
	 *   the result, and an unknown name yields an empty list.
	 * @param index the graph from [supertypeIndex], keyed by the same dotted binary names.
	 * @return superclasses and interfaces in breadth-first order, superclass before interfaces
	 *   at each level
	 */
	fun chainFor(
		className: String,
		index: Map<String, List<String>>,
	): List<String> {
		val chain = mutableListOf<String>()
		val seen = mutableSetOf(className)
		val queue = ArrayDeque(index[className].orEmpty())
		while (queue.isNotEmpty()) {
			val next = queue.removeFirst()
			if (next in index && seen.add(next)) {
				chain.add(next)
				queue.addAll(index[next].orEmpty())
			}
		}
		return chain
	}

	/**
	 * Reads one class header into an index entry.
	 *
	 * @param classBytes a whole `.class` file; only its header is parsed, so member bytecode may
	 *   reference types absent from this build.
	 * @return the class's dotted binary name paired with its direct supertypes, or null for a
	 *   class that declares none (`java.lang.Object`, `module-info`).
	 */
	private fun readHeader(classBytes: ByteArray): Pair<String, List<String>>? {
		val reader = ClassReader(classBytes)
		val supertypes =
			buildList {
				reader.superName?.let { add(it.replace('/', '.')) }
				reader.interfaces.forEach { add(it.replace('/', '.')) }
			}
		if (supertypes.isEmpty()) return null // java.lang.Object / module-info
		return reader.className.replace('/', '.') to supertypes
	}
}
