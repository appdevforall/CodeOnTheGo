package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * [QuickBuildPayloadTransformTask.divert] run directly on a [ProjectBuilder] task instance:
 * the divert must tolerate a declared-but-absent input the way its own retained-jar walk
 * already does, instead of throwing on the first missing file.
 */
class QuickBuildPayloadTransformTaskTest {
	@TempDir
	lateinit var tempDir: File

	private fun writeJar(
		file: File,
		vararg entries: String,
	): File {
		JarOutputStream(file.outputStream()).use { out ->
			entries.forEach { name ->
				out.putNextEntry(JarEntry(name))
				out.write(byteArrayOf(1, 2, 3))
				out.closeEntry()
			}
		}
		return file
	}

	@Test
	fun `a declared input that no longer exists is skipped, not thrown on`() {
		// AGP hands over declared artifact locations, not guaranteed files; a module whose
		// pipeline produced nothing (or a cleaned intermediate) must not fail the divert when
		// the sibling walkTopDown path already tolerates exactly that absence.
		val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
		val task = project.tasks.register("qbDivert", QuickBuildPayloadTransformTask::class.java).get()

		writeJar(File(tempDir, "present.jar"), "com/example/Foo.class", "com/example/R\$string.class")
		val presentDir = File(tempDir, "classes").apply { mkdirs() }
		File(presentDir, "com/example").mkdirs()
		File(presentDir, "com/example/Bar.class").writeBytes(byteArrayOf(4, 5))
		File(presentDir, "com/example/R.class").writeBytes(byteArrayOf(6, 7))

		// Missing entries FIRST, so index compaction is exercised too.
		val layout = project.layout.projectDirectory
		task.allJars.set(listOf(layout.file("missing.jar"), layout.file("present.jar")))
		task.allDirectories.set(listOf(layout.dir("missing-classes"), layout.dir("classes")))
		task.payloadClasses.set(project.layout.buildDirectory.dir("payload"))
		task.outputJar.set(project.layout.buildDirectory.file("retained.jar"))

		task.divert()

		val root = task.payloadClasses.get().asFile
		// Only the existing inputs are copied, compacted onto index 0.
		assertThat(File(root, "jars").listFiles()!!.map { it.name }).containsExactly("0.jar")
		assertThat(File(root, "dirs").listFiles()!!.map { it.name }).containsExactly("0")
		assertThat(File(root, "dirs/0/com/example/Bar.class").exists()).isTrue()
		// The retained-jar pass ran over the same filtered inputs: R classes from both kinds.
		val retained =
			JarFile(task.outputJar.get().asFile).use { jar ->
				jar
					.entries()
					.asSequence()
					.map { it.name }
					.toList()
			}
		assertThat(retained).contains("com/example/R.class")
		assertThat(retained).contains("com/example/R\$string.class")
	}
}
