package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ComponentProxiabilityResolverTest {
	private fun classBytes(
		access: Int,
		name: String,
	): ByteArray {
		val writer = ClassWriter(0)
		writer.visit(Opcodes.V11, access, name.replace('.', '/'), null, "java/lang/Object", null)
		writer.visitEnd()
		return writer.toByteArray()
	}

	@Test
	fun `a class not found on the library search path is assumed project-owned and proxiable`() {
		// Deliberate: this task runs before compilation, so it cannot check the project's own
		// compiled output without a task-graph cycle (see the class KDoc). Absence from the
		// library lookup is the "assume project code" default.
		val resolver = ComponentProxiabilityResolver(libraryClassBytes = { null })

		assertThat(resolver.resolve("com.example.app.MainActivity"))
			.isEqualTo(ComponentProxiabilityResolver.Resolution.Proxiable)
	}

	@Test
	fun `a final library class is not proxiable`() {
		val bytes = classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "androidx.room.MultiInstanceInvalidationService")
		val resolver = ComponentProxiabilityResolver(libraryClassBytes = { bytes })

		val resolution = resolver.resolve("androidx.room.MultiInstanceInvalidationService")

		assertThat(resolution).isInstanceOf(ComponentProxiabilityResolver.Resolution.Skip::class.java)
		assertThat((resolution as ComponentProxiabilityResolver.Resolution.Skip).reason).contains("final")
	}

	@Test
	fun `a non-final library class is proxiable`() {
		val bytes = classBytes(Opcodes.ACC_PUBLIC, "com.example.lib.SomeService")
		val resolver = ComponentProxiabilityResolver(libraryClassBytes = { bytes })

		assertThat(resolver.resolve("com.example.lib.SomeService"))
			.isEqualTo(ComponentProxiabilityResolver.Resolution.Proxiable)
	}

	@Test
	fun `byNameOnly skips only the named components`() {
		val resolver = ComponentProxiabilityResolver.byNameOnly()

		assertThat(resolver.resolve("anything.at.All")).isEqualTo(ComponentProxiabilityResolver.Resolution.Proxiable)
		assertThat(resolver.resolve("androidx.startup.InitializationProvider"))
			.isInstanceOf(ComponentProxiabilityResolver.Resolution.Skip::class.java)
		// Same self-lookup-by-name shape as InitializationProvider: renaming it makes Firebase
		// discover zero ComponentRegistrars, and initialization silently no-ops.
		assertThat(resolver.resolve("com.google.firebase.components.ComponentDiscoveryService"))
			.isInstanceOf(ComponentProxiabilityResolver.Resolution.Skip::class.java)
	}

	@Test
	fun `each by-name component is skipped with its own reason, whatever its class bytes say`() {
		// The by-name rules exist for what a class file CANNOT reveal, so they must win over
		// the final-class rule - including for a perfectly ordinary non-final class, which is
		// exactly what androidx.startup.InitializationProvider is.
		ComponentProxiabilityResolver.UNPROXIABLE_BY_NAME.forEach { (userClass, reason) ->
			val nonFinalBytes = classBytes(Opcodes.ACC_PUBLIC, userClass)

			val resolution = ComponentProxiabilityResolver { nonFinalBytes }.resolve(userClass)

			assertThat(resolution).isEqualTo(ComponentProxiabilityResolver.Resolution.Skip(reason))
		}
	}

	@Test
	fun `a by-name component stays skipped even when it looks project-owned`() {
		val userClass = "androidx.startup.InitializationProvider"

		val resolution =
			ComponentProxiabilityResolver { null }
				.resolveWithProjectOverride(userClass, projectClasses = setOf(userClass))

		assertThat(resolution).isInstanceOf(ComponentProxiabilityResolver.Resolution.Skip::class.java)
	}

	@Test
	fun `resolveWithProjectOverride keeps a project class Proxiable despite a raw final copy on the classpath`() {
		// The exact mixed-language regression (ADFA-4128): a Kotlin user Activity is final by
		// default in its raw bytecode, and ClassOpener only strips ACC_FINAL from the divert
		// task's OWN opened output. A mixed Kotlin/Java module's compile classpath can ALSO
		// expose a second, raw copy, which resolver.resolve() alone would read as final - so
		// project membership must win regardless of what the resolver would say.
		val userClass = "org.appdevforall.cotg.corpus.mixedlang.ui.MainActivity"
		val rawFinalCopy = classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, userClass)
		val resolver = ComponentProxiabilityResolver { rawFinalCopy }

		val resolution = resolver.resolveWithProjectOverride(userClass, projectClasses = setOf(userClass))

		assertThat(resolution).isEqualTo(ComponentProxiabilityResolver.Resolution.Proxiable)
	}

	@Test
	fun `resolveWithProjectOverride still defers to the resolver for a class not in projectClasses`() {
		val bytes = classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "androidx.room.MultiInstanceInvalidationService")
		val resolver = ComponentProxiabilityResolver { bytes }

		val resolution =
			resolver.resolveWithProjectOverride(
				"androidx.room.MultiInstanceInvalidationService",
				projectClasses = emptySet(),
			)

		assertThat(resolution).isInstanceOf(ComponentProxiabilityResolver.Resolution.Skip::class.java)
	}

	@Test
	fun `searchingClasspath finds a class in a directory search-path entry`(
		@TempDir tempDir: File,
	) {
		val classDir = File(tempDir, "classes")
		val relativePath = File(classDir, "androidx/room/MultiInstanceInvalidationService.class")
		relativePath.parentFile.mkdirs()
		relativePath.writeBytes(classBytes(Opcodes.ACC_PUBLIC, "androidx.room.MultiInstanceInvalidationService"))

		val resolver = ComponentProxiabilityResolver.searchingClasspath(listOf(classDir))

		assertThat(resolver.resolve("androidx.room.MultiInstanceInvalidationService"))
			.isEqualTo(ComponentProxiabilityResolver.Resolution.Proxiable)
	}

	@Test
	fun `searchingClasspath finds a final class inside a jar search-path entry and skips it`(
		@TempDir tempDir: File,
	) {
		val jar = File(tempDir, "room-runtime.jar")
		JarOutputStream(jar.outputStream()).use { out ->
			out.putNextEntry(JarEntry("androidx/room/MultiInstanceInvalidationService.class"))
			out.write(classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "androidx.room.MultiInstanceInvalidationService"))
			out.closeEntry()
		}

		val resolver = ComponentProxiabilityResolver.searchingClasspath(listOf(jar))

		val resolution = resolver.resolve("androidx.room.MultiInstanceInvalidationService")

		assertThat(resolution).isInstanceOf(ComponentProxiabilityResolver.Resolution.Skip::class.java)
		assertThat((resolution as ComponentProxiabilityResolver.Resolution.Skip).reason).contains("final")
	}

	@Test
	fun `searchingClasspath treats a class absent from every search-path entry as proxiable`(
		@TempDir tempDir: File,
	) {
		val emptyDir = File(tempDir, "empty").apply { mkdirs() }

		val resolver = ComponentProxiabilityResolver.searchingClasspath(listOf(emptyDir))

		assertThat(resolver.resolve("com.example.app.MainActivity"))
			.isEqualTo(ComponentProxiabilityResolver.Resolution.Proxiable)
	}

	@Test
	fun `searchingClasspath tolerates a corrupt jar on the search path, treating the class as not found`(
		@TempDir tempDir: File,
	) {
		val corruptJar = File(tempDir, "corrupt.jar").apply { writeText("not a real jar") }

		val resolver = ComponentProxiabilityResolver.searchingClasspath(listOf(corruptJar))

		assertThat(resolver.resolve("com.example.lib.Anything"))
			.isEqualTo(ComponentProxiabilityResolver.Resolution.Proxiable)
	}
}
