package com.itsaky.androidide.documentation

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.pebbletemplates.pebble.error.LoaderException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the loader that lets one template reference another (ADFA-5405): a name resolves to the
 * `Templates` row that carries it, and a name with no row fails loudly instead of resolving to
 * itself the way Pebble's `StringLoader` did.
 */
class DatabaseTemplateLoaderTest {
	private fun database(vararg templates: Pair<String, String>): SQLiteDatabase =
		mockk(relaxed = true) {
			every { rawQuery(any(), any()) } answers
				{
					val name = (secondArg<Array<String>>())[0]
					val body = templates.toMap()[name]
					mockk<Cursor>(relaxed = true) {
						every { moveToFirst() } returns (body != null)
						if (body != null) every { getBlob(0) } returns body.toByteArray()
					}
				}
		}

	private fun loader(database: SQLiteDatabase?) = DatabaseTemplateLoader { database }

	@Test
	fun `a name resolves to its template row`() {
		val reader = loader(database("nav.peb" to "[nav]")).getReader("nav.peb")

		assertThat(reader.readText()).isEqualTo("[nav]")
	}

	@Test
	fun `a name with no row fails, rather than resolving to itself`() {
		val loader = loader(database("nav.peb" to "[nav]"))

		val thrown = assertThrows(LoaderException::class.java) { loader.getReader("missing.peb") }

		assertThat(thrown).hasMessageThat().contains("missing.peb")
	}

	@Test
	fun `a resolution with no database open fails`() {
		val loader = loader(null)

		assertThrows(LoaderException::class.java) { loader.getReader("nav.peb") }
		assertThat(loader.resourceExists("nav.peb")).isFalse()
	}

	@Test
	fun `existence follows the table`() {
		val loader = loader(database("nav.peb" to "[nav]"))

		assertThat(loader.resourceExists("nav.peb")).isTrue()
		assertThat(loader.resourceExists("missing.peb")).isFalse()
	}

	@Test
	fun `names are used verbatim, since the table is a flat namespace`() {
		val loader = loader(database("nav.peb" to "[nav]"))
		loader.setPrefix("templates/")
		loader.setSuffix(".peb")
		loader.setCharset("ISO-8859-1")

		assertThat(loader.createCacheKey("nav.peb")).isEqualTo("nav.peb")
		assertThat(loader.resolveRelativePath("nav.peb", "k/html/page.peb")).isEqualTo("nav.peb")
		assertThat(loader.getReader("nav.peb").readText()).isEqualTo("[nav]")
	}
}
