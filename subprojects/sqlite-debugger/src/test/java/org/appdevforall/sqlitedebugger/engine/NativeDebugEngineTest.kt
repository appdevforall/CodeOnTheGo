package org.appdevforall.sqlitedebugger.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class NativeDebugEngineTest {
	@Test fun theRunIdIsARandomUuidSharedByEveryInstanceOfTheProcess() {
		val a = NativeDebugEngine().runId
		assertEquals(a, UUID.fromString(a).toString())
		assertEquals(a, NativeDebugEngine().runId)
	}
}
