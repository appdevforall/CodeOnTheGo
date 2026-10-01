package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * An open failure must surface as [UnreadableJarException] rather than being swallowed: silently
 * yielding nothing records the JAR as indexed with no symbols, and it is never retried.
 */
@RunWith(JUnit4::class)
class CombinedJarScannerUnreadableJarTest {
	@get:Rule
	val temp = TemporaryFolder()

	@Test
	fun `scanning a jar that cannot be opened throws UnreadableJarException`() {
		val garbage = temp.newFile("garbage.jar").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

		val exception =
			assertThrows(UnreadableJarException::class.java) {
				CombinedJarScanner.scan(garbage.toPath()).toList()
			}

		assertThat(exception.path).isEqualTo(garbage.toPath())
	}

	@Test
	fun `scanning a jar that no longer exists does not throw UnreadableJarException`() {
		val missing = temp.root.toPath().resolve("deleted.jar")

		val failure = runCatching { CombinedJarScanner.scan(missing).toList() }.exceptionOrNull()

		assertThat(failure).isNotNull()
		assertThat(failure).isNotInstanceOf(UnreadableJarException::class.java)
	}
}
