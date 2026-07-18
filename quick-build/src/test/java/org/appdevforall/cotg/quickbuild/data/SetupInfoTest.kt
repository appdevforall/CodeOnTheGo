package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class SetupInfoTest {
	private val baseDir = File("/project")

	private fun json(extra: String = "") =
		"""
		{
			"testAppId": "com.example.app.quickbuild",
			"entryActivity": "com.example.app.MainActivity",
			"apkPath": "/apk/app-debug.apk"
			$extra
		}
		""".trimIndent()

	@Test
	fun `composeEnabled true parses through`() {
		val info = SetupInfo.parse(json(""","composeEnabled": true"""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isTrue()
	}

	@Test
	fun `composeEnabled defaults to false when absent`() {
		val info = SetupInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isFalse()
	}

	@Test
	fun `composeEnabled tolerates a non-boolean value`() {
		val info = SetupInfo.parse(json(""","composeEnabled": "yes""""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isFalse()
	}
}
