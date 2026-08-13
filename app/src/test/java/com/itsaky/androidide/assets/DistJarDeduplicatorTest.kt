package com.itsaky.androidide.assets

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The offline Maven repo and the Gradle distribution are harvested independently and overlap by
 * construction, most expensively at `kotlin-compiler-embeddable`. Collapsing that overlap is only
 * safe if identity is decided by content: the two stores version independently, so a shared file
 * name proves nothing, and linking a same-named jar that is not the same jar would break a user's
 * build a long way from here.
 */
class DistJarDeduplicatorTest {
	@get:Rule
	val temp = TemporaryFolder()

	private lateinit var mavenRepo: File
	private lateinit var distLib: File

	private fun setUpTrees() {
		mavenRepo = temp.newFolder("localMvnRepository")
		distLib = temp.newFolder("gradle-dist-lib")
	}

	/** A jar at a realistic Maven coordinate path, since the walk has to recurse to find it. */
	private fun mavenJar(
		coordinate: String,
		name: String,
		content: String,
	): File {
		val dir = File(mavenRepo, coordinate)
		dir.mkdirs()
		return File(dir, name).apply { writeText(content) }
	}

	private fun distJar(
		name: String,
		content: String,
	): File = File(distLib, name).apply { writeText(content) }

	@Test
	fun `an identical jar becomes a link to the distribution copy`() {
		setUpTrees()
		val duplicate =
			mavenJar("org/jetbrains/kotlin/kotlin-stdlib/2.3.21", "kotlin-stdlib-2.3.21.jar", "same-bytes")
		val source = distJar("kotlin-stdlib-2.3.21.jar", "same-bytes")

		val outcome = DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		assertThat(Files.isSymbolicLink(duplicate.toPath())).isTrue()
		// Content still reachable at the Maven coordinate: Gradle resolves through this path and
		// must not be able to tell the difference.
		assertThat(duplicate.readText()).isEqualTo("same-bytes")
		assertThat(Files.readSymbolicLink(duplicate.toPath()).toFile().canonicalFile)
			.isEqualTo(source.canonicalFile)
		assertThat(outcome.linked).isEqualTo(1)
		assertThat(outcome.bytesReclaimed).isEqualTo("same-bytes".length.toLong())
	}

	@Test
	fun `a same-named jar with different content is left alone`() {
		setUpTrees()
		// The case that makes name-matching unsafe, and the reason the pre-filter is size while
		// the decision is the hash: same name, same length, different bytes.
		val duplicate =
			mavenJar("org/jetbrains/kotlin/kotlin-stdlib/2.3.21", "kotlin-stdlib-2.3.21.jar", "aaaaaaaaa")
		distJar("kotlin-stdlib-2.3.21.jar", "bbbbbbbbb")

		val outcome = DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		assertThat(Files.isSymbolicLink(duplicate.toPath())).isFalse()
		assertThat(duplicate.readText()).isEqualTo("aaaaaaaaa")
		assertThat(outcome.linked).isEqualTo(0)
	}

	@Test
	fun `identical content under a different name is still linked`() {
		setUpTrees()
		// Identity is the bytes, not the coordinate. A repackaged-but-identical artifact under
		// another name is just as safe to collapse.
		val duplicate = mavenJar("com/example/thing/1.0", "thing-1.0.jar", "identical")
		distJar("some-other-name.jar", "identical")

		val outcome = DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		assertThat(Files.isSymbolicLink(duplicate.toPath())).isTrue()
		assertThat(outcome.linked).isEqualTo(1)
	}

	@Test
	fun `a jar with no counterpart is left alone`() {
		setUpTrees()
		val untouched = mavenJar("com/example/only-here/1.0", "only-here-1.0.jar", "unique-bytes")
		distJar("something-else.jar", "different length entirely")

		val outcome = DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		assertThat(Files.isSymbolicLink(untouched.toPath())).isFalse()
		assertThat(untouched.readText()).isEqualTo("unique-bytes")
		assertThat(outcome.linked).isEqualTo(0)
	}

	@Test
	fun `a missing distribution is a no-op rather than a failure`() {
		setUpTrees()
		val untouched = mavenJar("com/example/thing/1.0", "thing-1.0.jar", "bytes")

		// An install must not fail because a space optimisation had nothing to work with.
		val outcome = DistJarDeduplicator.deduplicate(mavenRepo, File(temp.root, "never-extracted"))

		assertThat(outcome.linked).isEqualTo(0)
		assertThat(untouched.readText()).isEqualTo("bytes")
	}

	@Test
	fun `a missing maven repo is a no-op rather than a failure`() {
		setUpTrees()
		distJar("kotlin-stdlib-2.3.21.jar", "bytes")

		val outcome = DistJarDeduplicator.deduplicate(File(temp.root, "never-extracted"), distLib)

		assertThat(outcome.linked).isEqualTo(0)
	}

	@Test
	fun `a second pass over an already-linked tree changes nothing`() {
		setUpTrees()
		val duplicate = mavenJar("com/example/thing/1.0", "thing-1.0.jar", "same-bytes")
		distJar("thing-1.0.jar", "same-bytes")

		val first = DistJarDeduplicator.deduplicate(mavenRepo, distLib)
		val second = DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		// Every install re-runs this, so the steady state is "already linked". Re-linking a link
		// would be harmless but re-counting it would make the reclaimed figure a fiction.
		assertThat(first.linked).isEqualTo(1)
		assertThat(second.linked).isEqualTo(0)
		assertThat(second.bytesReclaimed).isEqualTo(0)
		assertThat(duplicate.readText()).isEqualTo("same-bytes")
	}

	@Test
	fun `non-jar files are never touched`() {
		setUpTrees()
		val pom = mavenJar("com/example/thing/1.0", "thing-1.0.pom", "same-bytes")
		distJar("thing-1.0.jar", "same-bytes")

		val outcome = DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		// POMs and .module files are small and Gradle reads them constantly; there is nothing to
		// win and a needless way to break resolution.
		assertThat(Files.isSymbolicLink(pom.toPath())).isFalse()
		assertThat(outcome.linked).isEqualTo(0)
	}

	@Test
	fun `several duplicates in one pass are all collapsed and counted`() {
		setUpTrees()
		val one = mavenJar("a/b/1.0", "one-1.0.jar", "first-content")
		val two = mavenJar("c/d/2.0", "two-2.0.jar", "second-content-longer")
		val notADuplicate = mavenJar("e/f/3.0", "three-3.0.jar", "no twin here at all")
		distJar("one-1.0.jar", "first-content")
		distJar("two-2.0.jar", "second-content-longer")

		val outcome = DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		assertThat(Files.isSymbolicLink(one.toPath())).isTrue()
		assertThat(Files.isSymbolicLink(two.toPath())).isTrue()
		assertThat(Files.isSymbolicLink(notADuplicate.toPath())).isFalse()
		assertThat(outcome.linked).isEqualTo(2)
		assertThat(outcome.bytesReclaimed)
			.isEqualTo(("first-content".length + "second-content-longer".length).toLong())
	}

	@Test
	fun `no staging leftovers survive a pass`() {
		setUpTrees()
		mavenJar("com/example/thing/1.0", "thing-1.0.jar", "same-bytes")
		distJar("thing-1.0.jar", "same-bytes")

		DistJarDeduplicator.deduplicate(mavenRepo, distLib)

		// The swap builds the link beside the file before moving it over; a leftover .dedup entry
		// would mean a partial swap that a later pass could trip over.
		val strays = mavenRepo.walkTopDown().filter { it.name.endsWith(".dedup") }.toList()
		assertThat(strays).isEmpty()
	}
}
