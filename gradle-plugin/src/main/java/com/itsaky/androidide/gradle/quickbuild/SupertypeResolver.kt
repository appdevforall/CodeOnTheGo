package com.itsaky.androidide.gradle.quickbuild

import org.objectweb.asm.ClassReader
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Reads user-side supertype edges from the diverted project classes: the restart closure
 * needs to know which project classes a service/provider/Application extends OR
 * implements, and only class-file headers are needed (a cheap constant-pool read, no
 * method bodies). A supertype NOT compiled by the project (framework/library) lives in
 * the base APK and never hot-swaps, so a walk stops at the first such class.
 *
 * Interfaces are recorded alongside the superclass. A project interface with default
 * method bodies is component code (that is why supertypes are in the restart closure at
 * all), and DeployPolicy's live index counts interface edges via `onClassHierarchy` - so
 * the baked seed must too. Omitting them makes an edit to such an interface at session
 * start a restart-policy false negative (design contract section 5) until the component
 * class itself happens to recompile in-session.
 */
internal object SupertypeResolver {
	/**
	 * FQN-to-direct-supertypes (superclass first, then interfaces) for every `.class`
	 * under [payloadClassesRoot] (the divert task's layout: `dirs/N/...` trees and
	 * `jars/N.jar`). Values include library supertypes too; [chainFor] filters to the
	 * project-compiled subset. Unreadable entries are skipped - a missing edge degrades
	 * to "restart decides without that supertype", never a crash.
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
	 * The project-compiled supertype closure of [className] - superclasses AND
	 * implemented interfaces, transitively, in breadth-first discovery order (superclass
	 * before interfaces at each level). Only classes present in [index] (i.e.
	 * project-compiled) are included; cycles are guarded.
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
