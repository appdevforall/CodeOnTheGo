package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class RuntimeClassesExtractorTest {
	private fun aarWith(
		dir: File,
		name: String,
		entries: Map<String, ByteArray>,
	): File {
		val aar = File(dir, name)
		JarOutputStream(aar.outputStream()).use { jar ->
			entries.forEach { (entryName, bytes) ->
				jar.putNextEntry(JarEntry(entryName))
				jar.write(bytes)
				jar.closeEntry()
			}
		}
		return aar
	}

	@Test
	fun `extracts classes jar preserving content`(
		@TempDir tempDir: File,
	) {
		val payload = "dex-adjacent bytes".toByteArray()
		val aar = aarWith(tempDir, "runtime.aar", mapOf("classes.jar" to payload, "R.txt" to ByteArray(0)))
		val outDir = File(tempDir, "out").apply { mkdirs() }

		val extracted = RuntimeClassesExtractor.extract(listOf(aar), outDir)

		assertThat(extracted).hasSize(1)
		assertThat(extracted.single().name).isEqualTo("runtime-classes.jar")
		assertThat(extracted.single().readBytes()).isEqualTo(payload)
	}

	@Test
	fun `skips an aar without a classes jar and non-aar files`(
		@TempDir tempDir: File,
	) {
		val bare = aarWith(tempDir, "bare.aar", mapOf("R.txt" to ByteArray(0)))
		val notAar = File(tempDir, "library.jar").apply { writeBytes(ByteArray(4)) }
		val outDir = File(tempDir, "out").apply { mkdirs() }

		assertThat(RuntimeClassesExtractor.extract(listOf(bare, notAar), outDir)).isEmpty()
	}

	@Test
	fun `a corrupt aar fails with a Quick Build attributed message`(
		@TempDir tempDir: File,
	) {
		val truncated = File(tempDir, "runtime.aar").apply { writeBytes(byteArrayOf(0x50, 0x4b)) }
		val outDir = File(tempDir, "out").apply { mkdirs() }

		val error =
			assertThrows<GradleException> {
				RuntimeClassesExtractor.extract(listOf(truncated), outDir)
			}
		assertThat(error).hasMessageThat().contains("Quick Build")
		assertThat(error).hasMessageThat().contains(truncated.absolutePath)
	}
}
