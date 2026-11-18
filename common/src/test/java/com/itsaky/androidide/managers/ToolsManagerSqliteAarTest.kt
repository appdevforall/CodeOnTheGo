package com.itsaky.androidide.managers

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * App init extracts the SQLite debugger and rewrite runtime AARs in the background, and a build gets their paths
 * without waiting, so [ToolsManager.replaceAtomically] must never leave a missing or partly written AAR at the path.
 */
class ToolsManagerSqliteAarTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	/** Hands out one byte per read, and records what a build reading [target] would find before each one. */
	private class WatchingStream(
		private val bytes: ByteArray,
		private val target: File,
	) : InputStream() {
		val seen = mutableListOf<String?>()
		private var next = 0

		override fun read(): Int {
			seen += if (target.isFile) target.readText() else null
			return if (next < bytes.size) bytes[next++].toInt() and 0xff else -1
		}

		override fun read(
			b: ByteArray,
			off: Int,
			len: Int,
		): Int {
			if (len == 0) return 0
			val c = read()
			if (c < 0) return -1
			b[off] = c.toByte()
			return 1
		}
	}

	@Test
	fun `a build that reads the AAR while it is replaced finds the previous complete file`() {
		val aar = File(tempFolder.root, "sqlite-debugger/sqlite-debugger.aar")
		aar.parentFile!!.mkdirs()
		aar.writeText("previous-install-aar")
		val source = WatchingStream("this-install-aar".toByteArray(), aar)

		ToolsManager.replaceAtomically(source, aar)

		assertThat(source.seen.toSet()).containsExactly("previous-install-aar")
		assertThat(aar.readText()).isEqualTo("this-install-aar")
		assertThat(File(aar.parentFile, "sqlite-debugger.aar.part").exists()).isFalse()
	}

	@Test
	fun `a copy that fails leaves the previous AAR and no temp file`() {
		val aar = File(tempFolder.root, "sqlite-debugger/sqlite-debugger.aar")
		aar.parentFile!!.mkdirs()
		aar.writeText("previous-install-aar")
		val failing =
			object : InputStream() {
				private var left = 4

				override fun read(): Int = if (left-- > 0) 'x'.code else throw IOException("asset read failed")
			}

		val e = assertThrows(IOException::class.java) { ToolsManager.replaceAtomically(failing, aar) }

		assertThat(e).hasMessageThat().isEqualTo("asset read failed")
		assertThat(aar.readText()).isEqualTo("previous-install-aar")
		assertThat(aar.parentFile!!.list()).asList().containsExactly("sqlite-debugger.aar")
	}

	@Test
	fun `the first extraction creates the directory and the AAR`() {
		val aar = File(tempFolder.root, "sqlite-rewrite-runtime/sqlite-rewrite-runtime.aar")

		ToolsManager.replaceAtomically("runtime-aar".byteInputStream(), aar)

		assertThat(aar.readText()).isEqualTo("runtime-aar")
	}
}
