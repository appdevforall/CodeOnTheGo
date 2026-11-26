package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.manager.services.sqltemplate.ProjectSqlFiles
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

	private fun SqlTemplateServiceImpl.renderPlain(path: String) = render(path, emptyMap<String, Any>(), emptyMap(), 1000)

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
		template("t.sql.peb", "SELECT 1")
		val onMain = service(mainThread = true)
		assertThrows(IllegalStateException::class.java) { onMain.listTemplates() }
		assertThrows(IllegalStateException::class.java) { onMain.getRoomSchemas() }
		assertThrows(IllegalStateException::class.java) { onMain.render("t.sql.peb", emptyMap<String, Any>(), emptyMap(), 1000) }
	}

	/** Runs [block] on a thread of its own and fails if it has not finished in 5 s, as a read of a FIFO would not. */
	private fun <T> within5s(block: () -> T): T {
		val result =
			java.util.concurrent.atomic
				.AtomicReference<Result<T>>()
		val thread = Thread { result.set(runCatching(block)) }.apply { isDaemon = true }
		thread.start()
		thread.join(5_000)
		check(result.get() != null) { "still running after 5 s" }
		return result.get().getOrThrow()
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
	fun `a template that is a FIFO is refused, not read`() {
		val fifo = File(root, "x.sql.peb")
		val made = ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0
		org.junit.Assume.assumeTrue("mkfifo is not available", made)
		val service = service()
		val e = assertThrows(SqlTemplateException::class.java) { within5s { service.renderPlain("x.sql.peb") } }
		assertThat(e).hasMessageThat().contains("x.sql.peb could not be read: not a regular file")
	}

	@Test
	fun `a folder named like a template is refused`() {
		File(root, "d.sql.peb").mkdirs()
		val e = assertThrows(SqlTemplateException::class.java) { service().render("d.sql.peb", emptyMap<String, Any>(), emptyMap(), 1000) }
		assertThat(e).hasMessageThat().contains("d.sql.peb could not be read")
	}

	@Test
	fun `a template larger than the limit is refused by what is read, not by the size the file system reports`() {
		template("big.sql.peb", "a".repeat(ProjectSqlFiles.MAX_FILE_BYTES.toInt() + 1))
		val e = assertThrows(SqlTemplateException::class.java) { service().renderPlain("big.sql.peb") }
		assertThat(e).hasMessageThat().contains("big.sql.peb is larger than 8 MiB")
	}

	@Test
	fun `a link to a template inside the project is read through to the template`() {
		template("real.sql.peb", "SELECT 7")
		Files.createSymbolicLink(File(root, "alias.sql.peb").toPath(), File(root, "real.sql.peb").toPath())
		assertThat(service().render("alias.sql.peb", emptyMap<String, Any>(), emptyMap(), 1000)).isEqualTo("SELECT 7")
	}

	@Test
	fun `a NUL in the path or the project folder is the documented error, not an IOException`() {
		template("t.sql.peb", "SELECT 1")
		assertThrows(IllegalArgumentException::class.java) { service().renderPlain("a\u0000b.sql.peb") }
		val nul = File("/tmp/pro\u0000ject")
		assertThrows(IllegalStateException::class.java) { service(project = nul).listTemplates() }
		assertThrows(IllegalStateException::class.java) { service(project = nul).getRoomSchemas() }
		assertThrows(IllegalStateException::class.java) { service(project = nul).renderPlain("t.sql.peb") }
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
