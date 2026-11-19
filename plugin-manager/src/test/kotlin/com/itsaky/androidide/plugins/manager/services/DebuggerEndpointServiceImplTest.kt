package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DebuggerEndpointServiceImplTest {
	private val project = File("/data/projects/demo")
	private val endpoint = DebuggerEndpoint("com.example.demo", 4321, "tok", 99L)
	private val seen = mutableListOf<String>()
	private val source =
		DebuggerEndpointSource { location ->
			seen += location
			if (location == project.canonicalPath) listOf(endpoint) else emptyList()
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
		assertThat(seen).containsExactly(project.canonicalPath)
	}

	@Test
	fun `asks the source by the canonical path, as recent_project_table stores it, for a root opened through a symlink`() {
		val dir = Files.createTempDirectory("proj").toFile()
		val links = Files.createTempDirectory("links").toFile()
		val link = File(links, "proj")
		try {
			Files.createSymbolicLink(link.toPath(), dir.toPath())
			val stored =
				DebuggerEndpointSource { location ->
					seen += location
					if (location == dir.canonicalPath) listOf(endpoint) else emptyList()
				}

			assertThat(service(root = link, source = stored).getEndpoints()).containsExactly(endpoint)
			assertThat(seen).containsExactly(dir.canonicalPath)
		} finally {
			// The link first, by itself: walking it would reach into the directory it points at.
			link.delete()
			links.delete()
			dir.delete()
		}
	}

	@Test
	fun `is empty and never asks the source when no project is open`() {
		assertThat(service(root = null).getEndpoints()).isEmpty()
		assertThat(seen).isEmpty()
	}

	@Test
	fun `the list returned cannot be modified, whatever list the source built`() {
		val endpoints = service(source = { arrayListOf(endpoint) }).getEndpoints()

		assertThrows(UnsupportedOperationException::class.java) { (endpoints as MutableList<DebuggerEndpoint>).add(endpoint) }
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
	fun `without a source, as when the app could not set one up, a call fails clearly instead of reporting no apps`() {
		for (root in listOf(project, null)) {
			val service = DebuggerEndpointServiceImpl("p", setOf(PluginPermission.DEBUGGER_ENDPOINTS), { root }, { null }, { false })

			val thrown = assertThrows(IllegalStateException::class.java) { service.getEndpoints() }
			assertThat(thrown).hasMessageThat().startsWith("The SQLite debugger endpoints are unavailable")
		}
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
