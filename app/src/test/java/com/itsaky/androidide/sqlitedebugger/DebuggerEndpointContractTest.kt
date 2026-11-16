package com.itsaky.androidide.sqlitedebugger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The library (:subprojects:sqlite-debugger) cannot share these constants and is not on this module's
 * classpath, so each side pins the same literals: EndpointBroadcastTest in the library checks the sender
 * against them. Change both together.
 */
class DebuggerEndpointContractTest {
	@Test
	fun `the broadcast names match what the library sends`() {
		assertThat(DebuggerEndpointValidation.ACTION).isEqualTo("com.itsaky.androidide.SQLITE_DEBUGGER_ENDPOINT")
		assertThat(DebuggerEndpointValidation.EXTRA_TOKEN).isEqualTo("token")
		assertThat(DebuggerEndpointValidation.EXTRA_PORT).isEqualTo("port")
		assertThat(DebuggerEndpointValidation.EXTRA_SENDER).isEqualTo("sender")
	}
}
