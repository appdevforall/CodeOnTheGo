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
	fun `refuses to read on the main thread`() {
		val onMain = service(mainThread = true)

		assertThrows(IllegalStateException::class.java) { onMain.getEndpoints() }
		assertThat(seen).isEmpty()
	}

	@Test
	fun `the default source is empty until the app installs one`() {
		DebuggerEndpointSources.install(null)
		val defaulted = DebuggerEndpointServiceImpl("p", setOf(PluginPermission.DEBUGGER_ENDPOINTS), { project }, isMainThread = { false })
		assertThat(defaulted.getEndpoints()).isEmpty()

		DebuggerEndpointSources.install(source)
		try {
			assertThat(defaulted.getEndpoints()).containsExactly(endpoint)
		} finally {
			DebuggerEndpointSources.install(null)
		}
	}
}
