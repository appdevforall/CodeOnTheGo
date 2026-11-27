package org.appdevforall.sqlitedebugger.mcp

/**
 * Text formats of the sqlite3 shell tools (ADFA-7301): CSV and SQL INSERT for export, the glob that
 * `.lint fkey-indexes` matches query plans with, and the database header `.dbinfo` reads. Pure, so
 * they are tested without an engine.
 */
internal object ShellFormat {
	/**
	 * One CSV field, RFC 4180: quoted when it holds a comma, a quote, CR or LF, with quotes doubled.
	 * NULL is an empty field and the empty string a quoted one ("") so the two stay apart; a BLOB is
	 * its hex; a REAL keeps every digit (Kotlin's shortest round-trip form).
	 */
	fun csvField(value: Any?): String =
		when (value) {
			null -> {
				""
			}

			is ByteArray -> {
				DatabaseTools.hex(value)
			}

			is Double -> {
				realText(value)
			}

			is String -> {
				if (value.isEmpty() || value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
					"\"" + value.replace("\"", "\"\"") + "\""
				} else {
					value
				}
			}

			else -> {
				value.toString()
			}
		}

	/**
	 * A value as an SQL literal that reads back the same: NULL, an integer, a REAL that round-trips
	 * (an infinity as 1e999, which SQLite reads as one; NaN as NULL, which is what SQLite stores for
	 * it), a quoted string with quotes doubled, or X'hex' for a BLOB.
	 */
	fun sqlLiteral(value: Any?): String =
		when (value) {
			null -> "NULL"
			is ByteArray -> "X'" + DatabaseTools.hex(value) + "'"
			is Double -> if (value.isNaN()) "NULL" else realText(value)
			is String -> "'" + value.replace("'", "''") + "'"
			else -> value.toString()
		}

	/** A REAL that SQLite reads back as REAL with the same value: always with a point or an exponent. */
	private fun realText(value: Double): String =
		when {
			value == Double.POSITIVE_INFINITY -> "1e999"
			value == Double.NEGATIVE_INFINITY -> "-1e999"
			value.isNaN() -> "NaN"
			else -> value.toString()
		}

	/** sqlite3_strglob as the shell uses it: `*` any run of characters, `?` any one, everything else literal. */
	fun globMatches(
		glob: String,
		text: String,
	): Boolean {
		val regex = StringBuilder()
		for (c in glob) {
			when (c) {
				'*' -> regex.append(".*")
				'?' -> regex.append('.')
				else -> regex.append(Regex.escape(c.toString()))
			}
		}
		return Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL).matches(text)
	}

	/**
	 * The fields of the 100-byte database header (page 1) that `.dbinfo` shows and no PRAGMA reads.
	 * Null when [page] is shorter than the header.
	 */
	fun headerFields(page: ByteArray): Map<String, Long>? {
		if (page.size < 100) return null

		fun u8(at: Int) = (page[at].toLong() and 0xff)

		fun u32(at: Int) = (u8(at) shl 24) or (u8(at + 1) shl 16) or (u8(at + 2) shl 8) or u8(at + 3)
		return linkedMapOf(
			"write_format" to u8(18),
			"read_format" to u8(19),
			"reserved_bytes" to u8(20),
			"file_change_counter" to u32(24),
			"schema_format" to u32(44),
			"default_cache_size" to u32(48),
			"autovacuum_top_root" to u32(52),
			"incremental_vacuum" to u32(64),
			"software_version" to u32(96),
		)
	}
}
