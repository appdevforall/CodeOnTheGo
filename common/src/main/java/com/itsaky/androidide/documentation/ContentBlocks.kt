/*
 *  This file is part of Code on the Go.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.documentation

import android.database.sqlite.SQLiteDatabase

/**
 * One item of a decoded content block (ADFA-6683, documentation version 4): bytes
 * `[offset, offset + length)`.
 *
 * The range is checked as 64-bit before it is narrowed to an index: `ContentBlockItems` columns are
 * SQLite INTEGERs, and an offset past [Int.MAX_VALUE] would otherwise wrap and slice the wrong bytes
 * without an error.
 *
 * @throws IllegalStateException When the range is not inside [block], which means the database is
 * corrupt.
 */
internal fun sliceBlockItem(
	block: ByteArray,
	blockId: Long,
	offset: Long,
	length: Long,
): ByteArray {
	check(offset >= 0 && length >= 0 && offset <= block.size - length) {
		"Item [$offset, +$length) is outside content block $blockId, which decodes to ${block.size} bytes"
	}
	return block.copyOfRange(offset.toInt(), (offset + length).toInt())
}

/**
 * Block [blockId]'s compressed bytes, in pieces of at most [pieceBytes], to be decoded back to back
 * (see [chunksAsStream]).
 *
 * A cursor holds its row in a CursorWindow, 2 MiB on Android, and a row that does not fit fails the
 * read. Only a block's decoded size is capped, not its compressed size, so a block over [pieceBytes]
 * is read a piece per query with `substr()`, which counts a BLOB's bytes from 1. SQLite loads the
 * whole blob for each of those queries, so a block of n pieces costs n reads of it; the largest
 * block in the 4.0.0 build, 559,581 bytes, is one piece and one query.
 *
 * @param path The page being served, for the failure.
 * @throws IllegalStateException When the database has no such block, or it has no data.
 */
internal fun readContentBlock(
	database: SQLiteDatabase,
	blockId: Long,
	path: String,
	pieceBytes: Int = DocumentationContentSource.CONTENT_CHUNK_SIZE,
): List<ByteArray> {
	val id = blockId.toString()
	val pieces = mutableListOf<ByteArray>()
	val size =
		database.rawQuery(BLOCK_QUERY, arrayOf(pieceBytes.toString(), id)).use { cursor ->
			check(cursor.moveToFirst()) { "Content block $blockId not found, for path '$path'" }
			check(!cursor.isNull(0)) { "Content block $blockId has no data, for path '$path'" }
			// substr() of an empty blob is NULL; that block then fails to decode, naming itself.
			pieces += cursor.getBlob(1) ?: ByteArray(0)
			cursor.getLong(0)
		}
	var read = pieces[0].size.toLong()
	while (read < size) {
		val piece =
			database.rawQuery(BLOCK_PIECE_QUERY, arrayOf((read + 1).toString(), pieceBytes.toString(), id)).use { cursor ->
				check(cursor.moveToFirst()) { "Content block $blockId not found, for path '$path'" }
				cursor.getBlob(0) ?: ByteArray(0)
			}
		// Empty only when the blob shrank under the read, which would otherwise loop for good.
		check(piece.isNotEmpty()) { "Content block $blockId ended at byte $read of $size, for path '$path'" }
		pieces += piece
		read += piece.size
	}
	return pieces
}

// The block's size and its first piece in one query, so a block of one piece costs one.
private const val BLOCK_QUERY = "SELECT length(data), substr(data, 1, ?) FROM ContentBlocks WHERE id = ?"

private const val BLOCK_PIECE_QUERY = "SELECT substr(data, ?, ?) FROM ContentBlocks WHERE id = ?"

/**
 * Decoded content blocks by key, least recently used out once their plain bytes would pass
 * [maxBytes]. A block larger than the whole budget is not cached.
 *
 * Thread-safe: every access holds this object's monitor. A caller decodes outside it, so a decode
 * never blocks another request's hit; two requests that miss the same block both decode it, and the
 * second [put] replaces the first.
 *
 * @param K What names a block: its `ContentBlocks.id`, and whatever else tells two files' blocks of
 * that id apart.
 */
internal class ContentBlockCache<K : Any>(
	private val maxBytes: Long,
) {
	// Access order, so iteration starts at the least recently used block.
	private val blocks = LinkedHashMap<K, ByteArray>(16, 0.75f, true)

	private var plainBytes = 0L

	/** The cached block, now the most recently used; null on a miss. */
	@Synchronized
	fun get(key: K): ByteArray? = blocks[key]

	/**
	 * Caches [plain] as [key]'s decoded block and evicts least recently used blocks to fit.
	 *
	 * @return The plain bytes cached after the insert.
	 */
	@Synchronized
	fun put(
		key: K,
		plain: ByteArray,
	): Long {
		blocks.remove(key)?.let { plainBytes -= it.size }
		if (plain.size > maxBytes) return plainBytes

		blocks[key] = plain
		plainBytes += plain.size
		// Never reaches the block just added: it is the newest, and it fits alone.
		val eldest = blocks.values.iterator()
		while (plainBytes > maxBytes) {
			plainBytes -= eldest.next().size
			eldest.remove()
		}
		return plainBytes
	}

	@Synchronized
	fun clear() {
		blocks.clear()
		plainBytes = 0
	}

	/** The plain bytes cached now. */
	@Synchronized
	fun plainBytes(): Long = plainBytes

	companion object {
		const val MAX_PLAIN_BYTES = 8L * 1024 * 1024
	}
}
