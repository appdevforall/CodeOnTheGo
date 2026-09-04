package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlin.test.Test
import kotlin.test.assertEquals

class SdkResolutionTest {
	@Test
	fun `sdk types load under this kotlin version`() {
		val info = Implementation(name = "cogo-mcp", version = "0.1.0")
		assertEquals("cogo-mcp", info.name)
		assertEquals("0.1.0", info.version)
	}
}
