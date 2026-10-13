package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class JarFingerprintTest {
	@get:Rule
	val temp = TemporaryFolder()

	@Test
	fun `rewriting a JAR with a different size changes its fingerprint`() {
		val jar = temp.newFile("lib.jar").apply { writeBytes(ByteArray(10)) }
		val before = jarFingerprint(jar)
		val mtime = jar.lastModified()

		jar.writeBytes(ByteArray(20))
		jar.setLastModified(mtime)

		assertThat(jarFingerprint(jar)).isNotEqualTo(before)
	}

	@Test
	fun `rewriting a JAR at the same size changes its fingerprint through the modification time`() {
		val jar = temp.newFile("lib.jar").apply { writeBytes(ByteArray(10)) }
		jar.setLastModified(1_000_000L)
		val before = jarFingerprint(jar)

		jar.writeBytes(ByteArray(10) { 1 })
		jar.setLastModified(2_000_000L)

		assertThat(jarFingerprint(jar)).isNotEqualTo(before)
	}
}
