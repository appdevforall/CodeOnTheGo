package com.itsaky.androidide.documentation

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
					// The two queries answer differently: a count always has a row, a template
					// lookup has one only when the template is there.
					val counting = firstArg<String>().contains("COUNT")
					mockk<Cursor>(relaxed = true) {
						every { moveToFirst() } returns (counting || body != null)
						every { getInt(0) } returns if (body != null) 1 else 0
						every { count } returns if (body != null) 1 else 0
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

	@Test
	fun `a duplicated name fails rather than picking a row by scan order`() {
		// The DDL declares UNIQUE('name'), but the open database may be a debug one dropped on the
		// sdcard, which is under no obligation to honour it. Taking row 0 would render the wrong
		// partial with no error and no log line.
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(any(), any()) } returns
					mockk<Cursor>(relaxed = true) {
						every { count } returns 2
						every { moveToFirst() } returns true
						every { getBlob(0) } returns "[nav]".toByteArray()
					}
			}

		val thrown = assertThrows(LoaderException::class.java) { loader(database).getReader("nav.peb") }

		assertThat(thrown).hasMessageThat().contains("nav.peb")
		assertThat(thrown).hasMessageThat().contains("more than one")
	}

	@Test
	fun `a row with no body fails by name, rather than throwing NullPointerException`() {
		// getBlob returns a platform type: a NULL content column yields null, and decoding it would
		// raise an NPE carrying neither a message nor the template name.
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(any(), any()) } returns
					mockk<Cursor>(relaxed = true) {
						every { count } returns 1
						every { moveToFirst() } returns true
						every { getBlob(0) } returns null
					}
			}

		val thrown = assertThrows(LoaderException::class.java) { loader(database).getReader("nav.peb") }

		assertThat(thrown).hasMessageThat().contains("nav.peb")
	}

	@Test
	fun `a database failure arrives as a loader failure, without the SQL`() {
		// Pebble does not wrap what a loader throws, so a raw SQLiteException would escape the
		// render's PebbleException catch carrying SQL text -- and one caller puts that message in an
		// HTTP response body on a port any app on the device can reach.
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(any(), any()) } throws
					SQLiteException("no such table: Templates (code 1): , while compiling: SELECT content FROM Templates")
			}

		val thrown = assertThrows(LoaderException::class.java) { loader(database).getReader("nav.peb") }

		assertThat(thrown).hasMessageThat().contains("nav.peb")
		assertThat(thrown).hasMessageThat().doesNotContain("SELECT")
	}

	@Test
	fun `an existence check that fails answers false rather than throwing`() {
		// Pebble's existence predicate, which a DelegatingLoader uses to decide whether to fall
		// through to the next loader: a throw aborts resolution where a miss would fall back. An
		// earlier version threw to keep SQL text out of the response, which broke the contract to
		// do it.
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(any(), any()) } throws
					SQLiteException("no such table: Templates (code 1): , while compiling: SELECT 1 FROM Templates")
			}

		assertThat(loader(database).resourceExists("nav.peb")).isFalse()
	}

	@Test
	fun `a duplicated name does not exist, since it cannot be loaded`() {
		// getReader refuses a name with more than one row, so a predicate that said yes to it would
		// promise something the reader then rejects.
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(any(), any()) } returns
					mockk<Cursor>(relaxed = true) {
						every { moveToFirst() } returns true
						every { getInt(0) } returns 2
					}
			}

		assertThat(loader(database).resourceExists("nav.peb")).isFalse()
	}
}
