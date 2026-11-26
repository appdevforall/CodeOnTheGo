package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.SqlTemplateException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class SqlTemplateServiceImplTest {
	private val root = Files.createTempDirectory("shop").toFile()
	private val outside = Files.createTempDirectory("outside").toFile()

	@After
	fun cleanUp() {
		root.deleteRecursively()
		outside.deleteRecursively()
	}

	private fun service(
		permissions: Set<PluginPermission> = setOf(PluginPermission.DEBUGGER_ENDPOINTS),
		project: File? = root,
		mainThread: Boolean = false,
	) = SqlTemplateServiceImpl("p", permissions, { project }, { mainThread }, { Instant.parse("2026-11-26T12:00:00Z") })

	private fun template(
		path: String,
		text: String,
	) = File(root, path).apply {
		parentFile!!.mkdirs()
		writeText(text)
	}

	@Test
	fun `renders a template of the project with project and render_time added`() {
		// The space keeps the line break: newline trimming eats one right after }}.
		template("sqlite/t.sql.peb", "-- \${{ project.name }} at \${{ render_time }} \nSELECT \${{ n }}")
		val sql = service().render("sqlite/t.sql.peb", mapOf("n" to 1L), emptyMap(), 5_000)
		assertThat(sql).isEqualTo("-- ${root.canonicalFile.name} at 2026-11-26T12:00:00Z \nSELECT 1")
	}

	@Test
	fun `needs the debugger permission`() {
		template("t.sql.peb", "SELECT 1")
		val noPermission = service(permissions = setOf(PluginPermission.FILESYSTEM_READ))
		assertThrows(SecurityException::class.java) { noPermission.render("t.sql.peb", emptyMap<String, Any>(), emptyMap(), 1000) }
		assertThrows(SecurityException::class.java) { noPermission.listTemplates() }
		assertThrows(SecurityException::class.java) { noPermission.getRoomSchemas() }
	}

	@Test
	fun `refuses the main thread`() {
		assertThrows(IllegalStateException::class.java) { service(mainThread = true).listTemplates() }
	}

	@Test
	fun `refuses a path outside the project, through dot-dot or a link, and a file that is not a template`() {
		File(outside, "x.peb").writeText("SELECT 1")
		assertThrows(IllegalArgumentException::class.java) {
			service().render("../${outside.name}/x.peb", emptyMap<String, Any>(), emptyMap(), 1000)
		}
		Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
		assertThrows(IllegalArgumentException::class.java) { service().render("link/x.peb", emptyMap<String, Any>(), emptyMap(), 1000) }
		template("build.gradle", "x")
		assertThrows(IllegalArgumentException::class.java) { service().render("build.gradle", emptyMap<String, Any>(), emptyMap(), 1000) }
		assertThrows(IllegalArgumentException::class.java) {
			service().render(File(outside, "x.peb").path, emptyMap<String, Any>(), emptyMap(), 1000)
		}
	}

	@Test
	fun `a reserved variable name is refused`() {
		template("t.sql.peb", "SELECT 1")
		assertThrows(IllegalArgumentException::class.java) { service().render("t.sql.peb", mapOf("project" to "x"), emptyMap(), 1000) }
	}

	@Test
	fun `a missing template is a template error, and no project is a state error`() {
		val e =
			assertThrows(SqlTemplateException::class.java) {
				service().render("nope.sql.peb", emptyMap<String, Any>(), emptyMap(), 1000)
			}
		assertThat(e).hasMessageThat().contains("nope.sql.peb could not be read")
		assertThrows(IllegalStateException::class.java) {
			service(project = null).render("t.sql.peb", emptyMap<String, Any>(), emptyMap(), 1000)
		}
		assertThat(service(project = null).listTemplates()).isEmpty()
		assertThat(service(project = null).getRoomSchemas()).isEmpty()
	}
}
