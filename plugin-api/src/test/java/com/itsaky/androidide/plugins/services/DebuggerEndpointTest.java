package com.itsaky.androidide.plugins.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.itsaky.androidide.plugins.PluginPermission;
import org.junit.Test;

public class DebuggerEndpointTest {

	@Test
	public void equalityCoversEveryField() {
		final DebuggerEndpoint base = new DebuggerEndpoint("a", 1, "t", 2L);
		assertEquals(base, new DebuggerEndpoint("a", 1, "t", 2L));
		assertEquals(base.hashCode(), new DebuggerEndpoint("a", 1, "t", 2L).hashCode());
		assertNotEquals(base, new DebuggerEndpoint("b", 1, "t", 2L));
		assertNotEquals(base, new DebuggerEndpoint("a", 9, "t", 2L));
		assertNotEquals(base, new DebuggerEndpoint("a", 1, "u", 2L));
		assertNotEquals(base, new DebuggerEndpoint("a", 1, "t", 3L));
	}

	@Test
	public void exposesWhatItWasBuiltWith() {
		final DebuggerEndpoint endpoint = new DebuggerEndpoint("com.example.app", 43210, "secret", 1234L);
		assertEquals("com.example.app", endpoint.getApplicationId());
		assertEquals(43210, endpoint.getPort());
		assertEquals("secret", endpoint.getToken());
		assertEquals(1234L, endpoint.getUpdatedAtMillis());
	}

	@Test
	public void nullApplicationIdOrTokenIsRejected() {
		try {
			new DebuggerEndpoint(null, 1, "t", 0L);
			fail();
		} catch (NullPointerException expected) {}
		try {
			new DebuggerEndpoint("a", 1, null, 0L);
			fail();
		} catch (NullPointerException expected) {}
	}

	@Test
	public void permissionKeyIsStable() {
		assertEquals("debugger.endpoints", PluginPermission.DEBUGGER_ENDPOINTS.getKey());
		assertTrue(DebuggerEndpointService.CONTRACT_VERSION >= 1);
	}

	@Test
	public void toStringDoesNotLeakTheToken() {
		assertFalse(new DebuggerEndpoint("a", 1, "hunter2", 2L).toString().contains("hunter2"));
	}
}
