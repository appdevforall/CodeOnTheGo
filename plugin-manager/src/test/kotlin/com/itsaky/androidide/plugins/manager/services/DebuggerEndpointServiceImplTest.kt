package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class DebuggerEndpointServiceImplTest {
	private val project = File("/data/projects/demo")
	private val endpoint = DebuggerEndpoint("com.example.demo", 4321, "tok", 99L)
	private val seen = mutableListOf<String>()
	private val source =
		DebuggerEndpointSource { location ->
			seen += location
			if (location == project.absolutePath) listOf(endpoint) else emptyList()
		}

	private fun service(
		permissions: Set<PluginPermission> = setOf(PluginPermission.DEBUGGER_ENDPOINTS),
		root: File? = project,
		source: DebuggerEndpointSource = this.source,
		mainThread: Boolean = false,
	) = DebuggerEndpointServiceImpl("p", permissions, { root }, { source }, { mainThread })

	@Test
	fun `returns the endpoints stored for the open project`() {
		assertThat(service().getEndpoints()).containsExactly(endpoint)
		assertThat(seen).containsExactly(project.absolutePath)
	}

	@Test
	fun `is empty and never asks the source when no project is open`() {
		assertThat(service(root = null).getEndpoints()).isEmpty()
		assertThat(seen).isEmpty()
	}

	@Test
	fun `is empty when the project has no endpoint`() {
		assertThat(service(root = File("/data/projects/other")).getEndpoints()).isEmpty()
	}

	@Test
	fun `refuses a plugin without the permission`() {
		val denied = service(permissions = setOf(PluginPermission.FILESYSTEM_READ, PluginPermission.NETWORK_ACCESS))

		assertThrows(SecurityException::class.java) { denied.getEndpoints() }
		assertThat(seen).isEmpty()
	}

	@Test
	fun `a failing source is reported, not mistaken for no apps`() {
		val cause = IllegalStateException("db closed")
		val failing = service(source = { throw cause })

		val thrown = assertThrows(IllegalStateException::class.java) { failing.getEndpoints() }
		assertThat(thrown).hasCauseThat().isSameInstanceAs(cause)
	}

	@Test
	fun `an interrupt from the source is rethrown unchanged and keeps the flag`() {
		val interrupted = service(source = { throw InterruptedException("stop") })

		try {
			val thrown = assertThrows(InterruptedException::class.java) { interrupted.getEndpoints() }
			assertThat(thrown).hasMessageThat().isEqualTo("stop")
			assertThat(Thread.currentThread().isInterrupted).isTrue()
		} finally {
			Thread.interrupted()
		}
	}

	@Test
	fun `a cancellation from the source is rethrown unchanged`() {
		val cancelled = service(source = { throw java.util.concurrent.CancellationException("cancelled") })

		val thrown = assertThrows(java.util.concurrent.CancellationException::class.java) { cancelled.getEndpoints() }
		assertThat(thrown).hasMessageThat().isEqualTo("cancelled")
	}

	@Test
	fun `refuses to read on the main thread`() {
		val onMain = service(mainThread = true)

		assertThrows(IllegalStateException::class.java) { onMain.getEndpoints() }
		assertThat(seen).isEmpty()
	}

	@Test
	fun `a closed service refuses until reopened`() {
		val service = service()
		service.close()

		assertThrows(java.util.concurrent.CancellationException::class.java) { service.getEndpoints() }
		assertThat(seen).isEmpty()

		service.reopen()

		assertThat(service.getEndpoints()).containsExactly(endpoint)
	}

	@Test
	fun `a plugin disabled during a read gets no tokens from it`() {
		lateinit var disabledMidRead: DebuggerEndpointServiceImpl
		disabledMidRead =
			service(source = {
				disabledMidRead.close()
				listOf(endpoint)
			})

		assertThrows(java.util.concurrent.CancellationException::class.java) { disabledMidRead.getEndpoints() }
	}

	@Test
	fun `is empty until the app installs a source`() {
		var installed: DebuggerEndpointSource? = null
		val service = DebuggerEndpointServiceImpl("p", setOf(PluginPermission.DEBUGGER_ENDPOINTS), { project }, { installed }, { false })
		assertThat(service.getEndpoints()).isEmpty()

		installed = source

		assertThat(service.getEndpoints()).containsExactly(endpoint)
	}

	@Test
	fun `adding the permission to the set the service was built from grants nothing`() {
		val granted = linkedSetOf(PluginPermission.FILESYSTEM_READ, PluginPermission.NETWORK_ACCESS)
		val denied = service(permissions = granted)

		granted += PluginPermission.DEBUGGER_ENDPOINTS

		assertThrows(SecurityException::class.java) { denied.getEndpoints() }
		assertThat(seen).isEmpty()
	}

	@Test
	fun `the service's own permission set cannot be added to, as a plugin would by reflection`() {
		val denied = service(permissions = linkedSetOf(PluginPermission.FILESYSTEM_READ, PluginPermission.NETWORK_ACCESS))
		val field = DebuggerEndpointServiceImpl::class.java.getDeclaredField("permissions").apply { isAccessible = true }

		@Suppress("UNCHECKED_CAST")
		val held = field.get(denied) as MutableSet<PluginPermission>
		assertThrows(UnsupportedOperationException::class.java) { held.add(PluginPermission.DEBUGGER_ENDPOINTS) }

		assertThrows(SecurityException::class.java) { denied.getEndpoints() }
		assertThat(seen).isEmpty()
	}
}
