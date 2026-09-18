package com.itsaky.androidide.assets

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * [JarLinks.trySymlink] is the single place both jar stores ask whether a link is possible, and its
 * two callers act on opposite answers - the stager copies, the deduplicator walks away - so a wrong
 * answer is silent either way. A false "true" leaves the daemon a classpath entry that is not
 * there; a "false" that damaged the target on the way out would destroy bytes the caller was
 * relying on still being present.
 */
class JarLinksTest {
	@get:Rule
	val temp = TemporaryFolder()

	@Test
	fun `a link is created and the source bytes read back through it`() {
		val source =
			File(temp.newFolder("dist-lib"), "kotlin-compiler-embeddable.jar")
				.apply { writeText("compiler-bytes") }
		val target = File(temp.newFolder("daemon"), "kotlin-compiler-embeddable.jar")

		assertThat(JarLinks.trySymlink(target, source)).isTrue()

		// The callers' contract is the bytes at [target], not the link itself: the daemon's manifest
		// Class-Path names this path and the JVM has to be able to open it.
		assertThat(Files.isSymbolicLink(target.toPath())).isTrue()
		assertThat(Files.readSymbolicLink(target.toPath()).toFile().canonicalFile)
			.isEqualTo(source.canonicalFile)
		assertThat(target.readText()).isEqualTo("compiler-bytes")
	}

	@Test
	fun `a refused link reports false and leaves the occupied target intact`() {
		val source =
			File(temp.newFolder("dist-lib"), "kotlin-stdlib.jar").apply { writeText("distribution-bytes") }
		// An occupied target is how a link gets refused in practice - it is exactly why
		// DistJarDeduplicator deletes its staging path before calling this. Whatever the cause, the
		// documented contract is the same: report false, touch nothing.
		val target =
			File(temp.newFolder("daemon"), "kotlin-stdlib.jar").apply { writeText("existing-bytes") }

		assertThat(JarLinks.trySymlink(target, source)).isFalse()

		assertThat(Files.isSymbolicLink(target.toPath())).isFalse()
		assertThat(target.readText()).isEqualTo("existing-bytes")
	}
}
