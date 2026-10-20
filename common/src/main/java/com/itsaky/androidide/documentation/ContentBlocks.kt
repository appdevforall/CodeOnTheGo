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

/**
 * One item of a decoded content block (ADFA-6683 prototype): bytes `[offset, offset + length)`.
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
 * Decoded content blocks by `ContentBlocks.id`, least recently used out once their plain bytes would
 * pass [maxBytes]. A block larger than the whole budget is not cached.
 *
 * Thread-safe: every access holds this object's monitor. A caller decodes outside it, so a decode
 * never blocks another request's hit; two requests that miss the same block both decode it, and the
 * second [put] replaces the first.
 */
internal class ContentBlockCache(
	private val maxBytes: Long,
) {
	// Access order, so iteration starts at the least recently used block.
	private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)

	private var plainBytes = 0L

	/** The cached block, now the most recently used; null on a miss. */
	@Synchronized
	fun get(blockId: Long): ByteArray? = blocks[blockId]

	/**
	 * Caches [plain] as [blockId]'s decoded block and evicts least recently used blocks to fit.
	 *
	 * @return The plain bytes cached after the insert.
	 */
	@Synchronized
	fun put(
		blockId: Long,
		plain: ByteArray,
	): Long {
		blocks.remove(blockId)?.let { plainBytes -= it.size }
		if (plain.size > maxBytes) return plainBytes

		blocks[blockId] = plain
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
