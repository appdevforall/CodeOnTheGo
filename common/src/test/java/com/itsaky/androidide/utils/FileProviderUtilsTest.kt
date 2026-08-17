package com.itsaky.androidide.utils

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/** Every `content://` Uri this app mints or checks must agree on the same FileProvider authority string. */
class FileProviderUtilsTest {
	@Test
	fun `fileProviderAuthorityFor appends the fixed suffix to the package name`() {
		assertThat(fileProviderAuthorityFor("com.itsaky.androidide"))
			.isEqualTo("com.itsaky.androidide.providers.fileprovider")
	}

	@Test
	fun `fileProviderAuthorityFor is stable across different package names`() {
		assertThat(fileProviderAuthorityFor("com.example.other"))
			.isEqualTo("com.example.other.providers.fileprovider")
	}

	@Test
	fun `Context fileProviderAuthority delegates to the context's package name`() {
		val context = mockk<Context>()
		every { context.packageName } returns "com.itsaky.androidide"

		assertThat(context.fileProviderAuthority())
			.isEqualTo(fileProviderAuthorityFor("com.itsaky.androidide"))
	}
}
