package com.itsaky.androidide.javac.services.fs

import com.google.common.truth.Truth.assertThat
import jdkx.lang.model.SourceVersion
import openjdk.tools.javac.file.RelativePath.RelativeDirectory
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CachedJarFileSystemPackageIndexTest {
	private lateinit var tmpDir: File
	private lateinit var jarPath: Path

	@Before
	fun setUp() {
		CachingJarFileSystemProvider.clearCache()
		tmpDir = Files.createTempDirectory("package-index").toFile()
		jarPath = File(tmpDir, "fixture.jar").toPath()
		writeFixtureJar(jarPath)
	}

	@After
	fun tearDown() {
		CachingJarFileSystemProvider.clearCache()
		tmpDir.deleteRecursively()
	}

	@Test
	fun `package index matches javac's own archive walk`() {
		val fs = openFs()

		val expected = walkLikeJavac(fs)

		assertThat(fs.packageIndex()).isEqualTo(expected)
		assertThat(expected).isNotEmpty()
	}

	@Test
	fun `package index is one instance across calls and providers`() {
		val fs = openFs()

		val first = fs.packageIndex()

		assertThat(fs.packageIndex()).isSameInstanceAs(first)
		assertThat(JarPackageProviderImpl.getPackages(jarPath)).isSameInstanceAs(first)
	}

	@Test
	fun `sixteen threads asking at once get one index`() {
		val fs = openFs()
		val threadCount = 16
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(threadCount)

		val futures =
			List(threadCount) {
				executor.submit<Map<RelativeDirectory, Path>> {
					start.await()
					fs.packageIndex()
				}
			}
		start.countDown()
		val results = futures.map { it.get(30, TimeUnit.SECONDS) }
		executor.shutdown()

		results.forEach { assertThat(it).isSameInstanceAs(results.first()) }
	}

	@Test
	fun `directories with non-identifier names are skipped with their subtrees`() {
		val keys = openFs().packageIndex().keys.map { it.path }

		assertThat(keys).contains("com/example/")
		assertThat(keys.none { it.startsWith("META-INF") }).isTrue()
		assertThat(keys.none { it.startsWith("2fast") }).isTrue()
	}

	private fun openFs() = CachingJarFileSystemProvider.newFileSystem(jarPath) as CachedJarFileSystem

	// Same logic as JavacFileManager.ArchiveContainer.walkArchive.
	private fun walkLikeJavac(fs: CachedJarFileSystem): Map<RelativeDirectory, Path> {
		val packages = HashMap<RelativeDirectory, Path>()
		for (root in fs.rootDirectories) {
			Files.walkFileTree(
				root,
				emptySet(),
				Int.MAX_VALUE,
				object : SimpleFileVisitor<Path>() {
					override fun preVisitDirectory(
						dir: Path,
						attrs: BasicFileAttributes,
					): FileVisitResult {
						val name = dir.fileName?.toString()?.removeSuffix("/")
						if (name != null && !SourceVersion.isIdentifier(name)) {
							return FileVisitResult.SKIP_SUBTREE
						}
						packages[RelativeDirectory(root.relativize(dir).toString())] = dir
						return FileVisitResult.CONTINUE
					}
				},
			)
		}
		return packages
	}
}

private fun writeFixtureJar(path: Path) {
	val entries =
		listOf(
			"META-INF/MANIFEST.MF",
			"META-INF/versions/9/a/B.class",
			"com/example/A.class",
			"com/example/sub/C.class",
			"org/other/D.class",
			"2fast/E.class",
		)
	ZipOutputStream(Files.newOutputStream(path)).use { zip ->
		entries.forEach {
			zip.putNextEntry(ZipEntry(it))
			zip.write(ByteArray(4))
			zip.closeEntry()
		}
	}
}
