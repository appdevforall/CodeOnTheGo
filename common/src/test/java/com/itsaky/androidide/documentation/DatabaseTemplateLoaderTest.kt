package com.itsaky.androidide.documentation

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.logging.provider.IdeLogRouter
import io.mockk.every
import io.mockk.mockk
import io.pebbletemplates.pebble.error.LoaderException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.slf4j.event.Level
import java.io.IOException

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

	// Rows here are stored as their text, so the decode is the identity unless a test says otherwise.
	private fun loader(
		database: SQLiteDatabase?,
		decode: (SQLiteDatabase, ByteArray) -> ByteArray = { _, body -> body },
	) = DatabaseTemplateLoader({ database }, decode)

	@Test
	fun `a name resolves to its template row`() {
		val reader = loader(database("nav.peb" to "[nav]")).getReader("nav.peb")

		assertThat(reader.readText()).isEqualTo("[nav]")
	}

	// From documentation version 3 a row holds the template Brotli-compressed, so Pebble has to be
	// handed what the decode returns, never the stored bytes.
	@Test
	fun `a row's bytes reach the engine through the decode, not as stored`() {
		val database = database("nav.peb" to "]van[")

		val reader = loader(database) { _, body -> body.reversedArray() }.getReader("nav.peb")

		assertThat(reader.readText()).isEqualTo("[nav]")
	}

	// Templates hold non-ASCII text: 640 of the 3.0.0 build's 1,126 do, most of them U+00A0. Read in
	// any charset but UTF-8, a no-break space reaches the page as "Â" plus a no-break space.
	@Test
	fun `a plain row's non-ASCII text reaches the engine as UTF-8`() {
		val reader = loader(database("nav.peb" to NON_ASCII)).getReader("nav.peb")

		assertThat(reader.readText()).isEqualTo(NON_ASCII)
	}

	@Test
	fun `a decoded row's non-ASCII text reaches the engine as UTF-8`() {
		val loader = loader(database("nav.peb" to "[nav]")) { _, _ -> NON_ASCII.toByteArray(Charsets.UTF_8) }

		assertThat(loader.getReader("nav.peb").readText()).isEqualTo(NON_ASCII)
	}

	@Test
	fun `a decode failure fails by name, as a loader failure`() {
		// A Brotli decode failure is an IOException: not a RuntimeException, so it would escape a
		// catch written for the database's own failures, and Pebble does not wrap it either.
		val loader = loader(database("nav.peb" to "[nav]")) { _, _ -> throw IOException("Brotli decoder failed") }

		val thrown = assertThrows(LoaderException::class.java) { loader.getReader("nav.peb") }

		// pebbleMessage, not message: the content source passes that on, without the "(?:?)" suffix.
		assertThat(thrown.pebbleMessage).isEqualTo("Cannot decode template 'nav.peb'")
	}

	// The exception's message reaches HTTP responses, so it names no file; the log line is where the
	// database is named, so a broken release file can be found from the log alone.
	@Test
	fun `a decode failure is logged with the template and the database`() {
		val database = database("nav.peb" to "[nav]")
		every { database.path } returns "/sdcard/Download/documentation.db"
		val errors = mutableListOf<String>()
		val sink = IdeLogRouter.ExternalSink { level, _, message, _ -> if (level == Level.ERROR) errors += message }
		IdeLogRouter.addSink(sink)
		try {
			val loader = loader(database) { _, _ -> throw IOException("Brotli decoder failed") }
			assertThrows(LoaderException::class.java) { loader.getReader("nav.peb") }
		} finally {
			IdeLogRouter.removeSink(sink)
		}

		assertThat(errors).hasSize(1)
		assertThat(errors.single()).contains("'nav.peb'")
		assertThat(errors.single()).contains("/sdcard/Download/documentation.db")
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

	private companion object {
		// A no-break space (in Latin-1) and an em dash and an ellipsis (outside it), as real templates hold.
		const val NON_ASCII = "a b — c…"
	}
}
