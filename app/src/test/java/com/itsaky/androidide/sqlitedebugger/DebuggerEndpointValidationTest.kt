package com.itsaky.androidide.sqlitedebugger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DebuggerEndpointValidationTest {
	private val token = "abcDEF012-_xyz"

	@Test
	fun `accepts a url-safe token and an in-range port`() {
		assertThat(DebuggerEndpointValidation.validate(token, 4242))
			.isEqualTo(DebuggerEndpointValidation.Endpoint(token, 4242))
		assertThat(DebuggerEndpointValidation.validate("a", 1)).isNotNull()
		assertThat(DebuggerEndpointValidation.validate("a".repeat(128), 65535)).isNotNull()
	}

	@Test
	fun `accepts the token McpHttp newToken produces`() {
		// 32 random bytes, URL-safe base64 without padding: 43 characters.
		assertThat(DebuggerEndpointValidation.validate("A".repeat(43), 80)).isNotNull()
	}

	@Test
	fun `rejects an empty or oversized token`() {
		assertThat(DebuggerEndpointValidation.validate("", 80)).isNull()
		assertThat(DebuggerEndpointValidation.validate("a".repeat(129), 80)).isNull()
	}

	@Test
	fun `rejects characters outside url-safe base64`() {
		for (bad in listOf("a b", "a+b", "a/b", "abc=", "a\nb", "a.b", "toké")) {
			assertThat(DebuggerEndpointValidation.validate(bad, 80)).isNull()
		}
	}

	@Test
	fun `rejects ports out of range`() {
		assertThat(DebuggerEndpointValidation.validate(token, 0)).isNull()
		assertThat(DebuggerEndpointValidation.validate(token, 65536)).isNull()
		assertThat(DebuggerEndpointValidation.validate(token, -1)).isNull()
	}

	@Test
	fun `rejects missing extras`() {
		assertThat(DebuggerEndpointValidation.validate(null, 80)).isNull()
		assertThat(DebuggerEndpointValidation.validate(token, null)).isNull()
		assertThat(DebuggerEndpointValidation.validate(null, null)).isNull()
	}

	@Test
	fun `a creator package is the sender`() {
		assertThat(DebuggerEndpointValidation.resolveSender("real.app")).isEqualTo("real.app")
	}

	@Test
	fun `no creator package is ignored`() {
		assertThat(DebuggerEndpointValidation.resolveSender(null)).isNull()
		assertThat(DebuggerEndpointValidation.resolveSender("")).isNull()
		assertThat(DebuggerEndpointValidation.resolveSender("a".repeat(256))).isNull()
	}
}
