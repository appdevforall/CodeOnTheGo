/*
 *  This file is part of AndroidIDE.
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

package com.itsaky.androidide.zipfs2.listing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipException;

/**
 * An immutable, packed index of the entries of one JAR, built from its central directory.
 *
 * <p>Entries are identified by an {@code int} index. Index 0 is the root, named {@code "/"}.
 * Names are stored like zipfs2's {@code IndexNode}: a leading {@code /}, no trailing {@code /}
 * and no {@code //}. Parent directories missing from the archive are synthesized. Storage is a
 * handful of primitive arrays, with no per-entry objects.
 *
 * <p>Instances are safe for use by multiple threads.
 */
public final class JarListing {

	private static final int ENDHDR = 22;
	private static final int END_MAXLEN = 0xFFFF + ENDHDR;
	private static final int ZIP64_LOCHDR = 20;
	private static final int ZIP64_ENDHDR = 56;
	private static final int CENHDR = 46;
	private static final long CENSIG = 0x02014b50L;
	private static final long ENDSIG = 0x06054b50L;
	private static final long ZIP64_LOCSIG = 0x07064b50L;
	private static final long ZIP64_ENDSIG = 0x06064b50L;
	private static final int ZIP64_EXTID = 0x0001;
	private static final long ZIP64_MINVAL = 0xFFFFFFFFL;
	private static final int ZIP64_MINVAL32 = 0xFFFF;
	private static final int FLAG_ENCRYPTED = 0x01;
	private static final int METHOD_STORED = 0;
	private static final int METHOD_DEFLATED = 8;

	private static final int KIND_DIRECTORY_BIT = 1 << 16;
	private static final int KIND_METHOD_SHIFT = 17;
	private static final int KIND_FLAG_MASK = 0xFFFF;
	private static final int SYNTHESIZED = 0;
	private static final int MAX_KINDS = 256;

	private static final int OVERFLOW_COMPRESSED = 0;
	private static final int OVERFLOW_SIZE = 1;
	private static final int OVERFLOW_OFFSET = 2;
	private static final int OVERFLOW_FIELDS = 3;

	private final int count;
	private final byte[] names;
	private final int[] nameOffsets;
	private final int[] table;
	private final int[] firstChildren;
	private final int[] nextSiblings;

	/* Index into kinds[] per entry; 0 marks a directory synthesized for lack of an entry. */
	private final byte[] kindIndex;
	private final int[] kinds;
	private final int[] crcs;
	private final int[] compressedSizes;
	private final int[] sizes;
	private final int[] offsets;
	private final int[] dosTimes;

	/*
	 * Values that do not fit in 32 bits (zip64). The per-entry int holds -1 and the real value is
	 * found by binary search of overflowEntries.
	 */
	private final int[] overflowEntries;
	private final long[] overflowValues;

	private JarListing(Builder b) {
		this.count = b.count;
		this.names = Arrays.copyOf(b.names, b.namesUsed);
		this.nameOffsets = Arrays.copyOf(b.nameOffsets, count + 1);
		this.firstChildren = Arrays.copyOf(b.firstChildren, count);
		this.nextSiblings = Arrays.copyOf(b.nextSiblings, count);
		this.kindIndex = Arrays.copyOf(b.kindIndex, count);
		this.kinds = Arrays.copyOf(b.kinds, b.kindCount);
		this.crcs = Arrays.copyOf(b.crcs, count);
		this.compressedSizes = Arrays.copyOf(b.compressedSizes, count);
		this.sizes = Arrays.copyOf(b.sizes, count);
		this.offsets = Arrays.copyOf(b.offsets, count);
		this.dosTimes = Arrays.copyOf(b.dosTimes, count);
		this.table = buildTable(names, nameOffsets, count);

		this.overflowEntries = new int[b.overflow.size()];
		int slot = 0;
		for (int entry : b.overflow.keySet()) {
			overflowEntries[slot++] = entry;
		}
		Arrays.sort(overflowEntries);
		this.overflowValues = new long[overflowEntries.length * OVERFLOW_FIELDS];
		for (slot = 0; slot < overflowEntries.length; slot++) {
			System.arraycopy(b.overflow.get(overflowEntries[slot]), 0, overflowValues,
					slot * OVERFLOW_FIELDS, OVERFLOW_FIELDS);
		}
	}

	/**
	 * Reads the central directory of the given JAR in one pass and indexes its entries.
	 *
	 * @throws ZipException if the central directory is malformed or the file is not a zip
	 * @throws IOException  if the file cannot be read
	 */
	public static JarListing read(Path jar) throws IOException {
		try (FileChannel ch = FileChannel.open(jar, StandardOpenOption.READ)) {
			Directory directory = locateDirectory(ch);
			byte[] cen = new byte[(int) directory.length];
			readFully(ch, ByteBuffer.wrap(cen), directory.position);
			return new Builder(directory.entryHint, directory.localHeaderBase).parse(cen).finish();
		}
	}

	/** Returns the number of entries, including the root and synthesized directories. */
	public int size() {
		return count;
	}

	/** Returns the index of the entry with the given normalized name, or -1 if absent. */
	public int find(byte[] name) {
		return lookup(table, names, nameOffsets, name, 0, name.length);
	}

	/** Returns the index of the entry with the given normalized name, or -1 if absent. */
	public int find(String name) {
		return find(name.getBytes(StandardCharsets.UTF_8));
	}

	/** Returns whether the entry is a directory, explicit or synthesized. */
	public boolean isDirectory(int i) {
		return i == 0 || kindIndex[i] == SYNTHESIZED || (kinds[kindIndex[i] & 0xFF] & KIND_DIRECTORY_BIT) != 0;
	}

	/** Returns the index of the entry's parent directory, or -1 for the root. */
	public int parent(int i) {
		if (i == 0) {
			return -1;
		}
		int start = nameOffsets[i];
		int slash = nameOffsets[i + 1] - 1;
		while (names[slash] != '/') {
			slash--;
		}
		return slash == start ? 0 : lookup(table, names, nameOffsets, names, start, slash - start);
	}

	/** Returns the index of the entry's first child, or -1 if it has none. Child order is unspecified. */
	public int firstChild(int i) {
		return firstChildren[i];
	}

	/** Returns the index of the next entry with the same parent, or -1 after the last one. */
	public int nextSibling(int i) {
		return nextSiblings[i];
	}

	/** Returns the full normalized name of the entry. */
	public String name(int i) {
		return new String(names, nameOffsets[i], nameOffsets[i + 1] - nameOffsets[i], StandardCharsets.UTF_8);
	}

	/** Returns the last path segment of the entry's name, or the empty string for the root. */
	public String shortName(int i) {
		if (i == 0) {
			return "";
		}
		int end = nameOffsets[i + 1];
		int slash = end - 1;
		while (names[slash] != '/') {
			slash--;
		}
		return new String(names, slash + 1, end - slash - 1, StandardCharsets.UTF_8);
	}

	/** Returns a copy of the full normalized name of the entry. */
	public byte[] nameBytes(int i) {
		return Arrays.copyOfRange(names, nameOffsets[i], nameOffsets[i + 1]);
	}

	/** Returns the compression method of the entry, or 0 for a synthesized directory. */
	public int method(int i) {
		return kinds[kindIndex[i] & 0xFF] >>> KIND_METHOD_SHIFT;
	}

	/** Returns the general purpose bit flag of the entry, or 0 for a synthesized directory. */
	public int flag(int i) {
		return kinds[kindIndex[i] & 0xFF] & KIND_FLAG_MASK;
	}

	/** Returns the CRC-32 of the uncompressed data. */
	public long crc(int i) {
		return crcs[i] & 0xFFFFFFFFL;
	}

	/** Returns the compressed size in bytes, or 0 for a synthesized directory. */
	public long compressedSize(int i) {
		return widen(compressedSizes[i], i, OVERFLOW_COMPRESSED);
	}

	/** Returns the uncompressed size in bytes, or 0 for a synthesized directory. */
	public long size(int i) {
		return widen(sizes[i], i, OVERFLOW_SIZE);
	}

	/**
	 * Returns the file position of the entry's local header, including any prefix before the
	 * archive, or -1 for a synthesized directory.
	 */
	public long localHeaderOffset(int i) {
		if (i == 0 || kindIndex[i] == SYNTHESIZED) {
			return -1L;
		}
		return widen(offsets[i], i, OVERFLOW_OFFSET);
	}

	/** Returns the entry's modification time as the raw MS-DOS date and time word. */
	public long dosTime(int i) {
		return dosTimes[i] & 0xFFFFFFFFL;
	}

	/** Returns the total length in bytes of all arrays this listing retains. */
	public long retainedBytes() {
		return names.length
				+ 4L * (nameOffsets.length + table.length + firstChildren.length + nextSiblings.length
						+ kinds.length + crcs.length + compressedSizes.length + sizes.length
						+ offsets.length + dosTimes.length + overflowEntries.length)
				+ kindIndex.length
				+ 8L * overflowValues.length;
	}

	private long widen(int stored, int i, int field) {
		if (stored != -1) {
			return stored & 0xFFFFFFFFL;
		}
		int slot = Arrays.binarySearch(overflowEntries, i);
		return overflowValues[slot * OVERFLOW_FIELDS + field];
	}

	private static int hash(byte[] a, int off, int len) {
		int h = 1;
		for (int k = off; k < off + len; k++) {
			h = 31 * h + a[k];
		}
		return h ^ (h >>> 16);
	}

	/* Slots hold entry index + 1, so 0 means empty. */
	private static int lookup(int[] table, byte[] names, int[] nameOffsets, byte[] key, int off, int len) {
		int slot = (hash(key, off, len) & 0x7FFFFFFF) % table.length;
		int stored;
		while ((stored = table[slot]) != 0) {
			int i = stored - 1;
			int start = nameOffsets[i];
			if (nameOffsets[i + 1] - start == len && regionEquals(names, start, key, off, len)) {
				return i;
			}
			if (++slot == table.length) {
				slot = 0;
			}
		}
		return -1;
	}

	private static boolean regionEquals(byte[] a, int aOff, byte[] b, int bOff, int len) {
		for (int k = 0; k < len; k++) {
			if (a[aOff + k] != b[bOff + k]) {
				return false;
			}
		}
		return true;
	}

	private static void insert(int[] table, byte[] names, int[] nameOffsets, int i) {
		int start = nameOffsets[i];
		int slot = (hash(names, start, nameOffsets[i + 1] - start) & 0x7FFFFFFF) % table.length;
		while (table[slot] != 0) {
			if (++slot == table.length) {
				slot = 0;
			}
		}
		table[slot] = i + 1;
	}

	private static int[] buildTable(byte[] names, int[] nameOffsets, int count) {
		int[] table = new int[count + count / 3 + 2];
		for (int i = 0; i < count; i++) {
			insert(table, names, nameOffsets, i);
		}
		return table;
	}

	private static int sh(byte[] b, int pos) {
		return (b[pos] & 0xFF) | ((b[pos + 1] & 0xFF) << 8);
	}

	private static long lg(byte[] b, int pos) {
		return (sh(b, pos) | ((long) sh(b, pos + 2) << 16)) & 0xFFFFFFFFL;
	}

	private static long ll(byte[] b, int pos) {
		return lg(b, pos) | (lg(b, pos + 4) << 32);
	}

	private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
		while (buf.hasRemaining()) {
			int n = ch.read(buf, position + buf.position());
			if (n < 0) {
				throw new ZipException("read CEN tables failed");
			}
		}
	}

	private static final class Directory {
		long position;
		long length;
		long localHeaderBase;
		int entryHint;
	}

	/* Mirrors ZipFileSystem.findEND and the start of initCEN. */
	private static Directory locateDirectory(FileChannel ch) throws IOException {
		long fileLength = ch.size();
		if (fileLength < ENDHDR) {
			throw new ZipException("zip END header not found");
		}
		int tailLength = (int) Math.min(fileLength, END_MAXLEN);
		long tailStart = fileLength - tailLength;
		byte[] tail = new byte[tailLength];
		readFully(ch, ByteBuffer.wrap(tail), tailStart);

		int at = -1;
		for (int i = tailLength - ENDHDR; i >= 0; i--) {
			if (lg(tail, i) == ENDSIG && tailStart + i + ENDHDR + sh(tail, i + 20) == fileLength) {
				at = i;
				break;
			}
		}
		if (at < 0) {
			throw new ZipException("zip END header not found");
		}

		long entries = sh(tail, at + 10);
		long cenLength = lg(tail, at + 12);
		long cenOffset = lg(tail, at + 16);
		long endPosition = tailStart + at;

		if (endPosition >= ZIP64_LOCHDR) {
			byte[] locator = new byte[ZIP64_LOCHDR];
			readFully(ch, ByteBuffer.wrap(locator), endPosition - ZIP64_LOCHDR);
			if (lg(locator, 0) == ZIP64_LOCSIG) {
				long end64Position = ll(locator, 8);
				byte[] end64 = new byte[ZIP64_ENDHDR];
				if (end64Position >= 0 && end64Position + ZIP64_ENDHDR <= fileLength) {
					readFully(ch, ByteBuffer.wrap(end64), end64Position);
					long total64 = ll(end64, 32);
					long length64 = ll(end64, 40);
					long offset64 = ll(end64, 48);
					boolean consistent = lg(end64, 0) == ZIP64_ENDSIG
							&& (length64 == cenLength || cenLength == ZIP64_MINVAL)
							&& (offset64 == cenOffset || cenOffset == ZIP64_MINVAL)
							&& (total64 == entries || entries == ZIP64_MINVAL32);
					if (consistent) {
						cenLength = length64;
						cenOffset = offset64;
						entries = total64;
						endPosition = end64Position;
					}
				}
			}
		}

		if (endPosition == 0) {
			Directory empty = new Directory();
			empty.length = 0;
			return empty;
		}
		if (cenLength < 0 || cenLength > endPosition) {
			throw new ZipException("invalid END header (bad central directory size)");
		}
		if (cenLength > Integer.MAX_VALUE - ENDHDR) {
			throw new ZipException("invalid END header (central directory too large)");
		}
		long cenPosition = endPosition - cenLength;
		long base = cenPosition - cenOffset;
		if (base < 0) {
			throw new ZipException("invalid END header (bad central directory offset)");
		}
		Directory directory = new Directory();
		directory.position = cenPosition;
		directory.length = cenLength;
		directory.localHeaderBase = base;
		directory.entryHint = (int) Math.min(entries, cenLength / (CENHDR + 1));
		return directory;
	}

	private static final class Builder {
		private final long localHeaderBase;
		private final Map<Integer, long[]> overflow = new HashMap<>();
		private final int[] kinds = new int[MAX_KINDS];
		private int kindCount = 1;

		private int count;
		private int capacity;
		private byte[] names;
		private int namesUsed;
		private int[] nameOffsets;
		private int[] table;
		private int[] firstChildren;
		private int[] nextSiblings;
		private byte[] kindIndex;
		private int[] crcs;
		private int[] compressedSizes;
		private int[] sizes;
		private int[] offsets;
		private int[] dosTimes;

		private byte[] scratch = new byte[256];

		Builder(int entryHint, long localHeaderBase) {
			this.localHeaderBase = localHeaderBase;
			this.kinds[SYNTHESIZED] = KIND_DIRECTORY_BIT;
			this.capacity = entryHint + 8;
			this.names = new byte[capacity * 24];
			this.nameOffsets = new int[capacity + 1];
			this.table = new int[Integer.highestOneBit(capacity * 2) * 2];
			this.firstChildren = new int[capacity];
			this.nextSiblings = new int[capacity];
			this.kindIndex = new byte[capacity];
			this.crcs = new int[capacity];
			this.compressedSizes = new int[capacity];
			this.sizes = new int[capacity];
			this.offsets = new int[capacity];
			this.dosTimes = new int[capacity];
			append(new byte[] {'/'}, 0, 1);
		}

		Builder parse(byte[] cen) throws ZipException {
			int pos = 0;
			int limit = cen.length;
			while (pos < limit) {
				if (limit - pos < CENHDR || lg(cen, pos) != CENSIG) {
					throw new ZipException("invalid CEN header (bad signature)");
				}
				int flag = sh(cen, pos + 8);
				int method = sh(cen, pos + 10);
				int nlen = sh(cen, pos + 28);
				int elen = sh(cen, pos + 30);
				int clen = sh(cen, pos + 32);
				if ((flag & FLAG_ENCRYPTED) != 0) {
					throw new ZipException("invalid CEN header (encrypted entry)");
				}
				if (method != METHOD_STORED && method != METHOD_DEFLATED) {
					throw new ZipException("invalid CEN header (unsupported compression method: " + method + ")");
				}
				if ((long) pos + CENHDR + nlen + elen + clen > limit) {
					throw new ZipException("invalid CEN header (bad header size)");
				}
				addEntry(cen, pos, flag, method, nlen, elen);
				pos += CENHDR + nlen + elen + clen;
			}
			return this;
		}

		JarListing finish() {
			return new JarListing(this);
		}

		private void addEntry(byte[] cen, int pos, int flag, int method, int nlen, int elen)
				throws ZipException {
			int nameStart = pos + CENHDR;
			boolean directory = nlen > 0 && cen[nameStart + nlen - 1] == '/';
			int len = normalizeInto(cen, nameStart, nlen);

			long size = lg(cen, pos + 24);
			long compressed = lg(cen, pos + 20);
			long offset = lg(cen, pos + 42);
			if (size == ZIP64_MINVAL || compressed == ZIP64_MINVAL || offset == ZIP64_MINVAL) {
				long[] wide = readZip64(cen, nameStart + nlen, elen, size, compressed, offset);
				size = wide[0];
				compressed = wide[1];
				offset = wide[2];
			}

			int i = find(scratch, 0, len);
			if (i < 0) {
				i = create(scratch, len);
			}
			int kind = kindFor(method, directory, flag);
			kindIndex[i] = (byte) kind;
			crcs[i] = (int) lg(cen, pos + 16);
			dosTimes[i] = (int) lg(cen, pos + 12);
			store(i, size, compressed, offset + localHeaderBase);
		}

		private void store(int i, long size, long compressed, long offset) {
			sizes[i] = narrow(size);
			compressedSizes[i] = narrow(compressed);
			offsets[i] = narrow(offset);
			if (sizes[i] == -1 || compressedSizes[i] == -1 || offsets[i] == -1) {
				long[] wide = new long[OVERFLOW_FIELDS];
				wide[OVERFLOW_SIZE] = size;
				wide[OVERFLOW_COMPRESSED] = compressed;
				wide[OVERFLOW_OFFSET] = offset;
				overflow.put(i, wide);
			} else {
				overflow.remove(i);
			}
		}

		private static int narrow(long v) {
			return v >= ZIP64_MINVAL ? -1 : (int) v;
		}

		private static long[] readZip64(byte[] cen, int extraStart, int elen, long size, long compressed,
				long offset) throws ZipException {
			int p = extraStart;
			int end = extraStart + elen;
			while (p + 4 <= end) {
				int id = sh(cen, p);
				int dataLen = sh(cen, p + 2);
				int data = p + 4;
				if (data + dataLen > end) {
					break;
				}
				if (id == ZIP64_EXTID) {
					int at = data;
					int dataEnd = data + dataLen;
					if (size == ZIP64_MINVAL) {
						size = nextLong(cen, at, dataEnd);
						at += 8;
					}
					if (compressed == ZIP64_MINVAL) {
						compressed = nextLong(cen, at, dataEnd);
						at += 8;
					}
					if (offset == ZIP64_MINVAL) {
						offset = nextLong(cen, at, dataEnd);
					}
					return new long[] {size, compressed, offset};
				}
				p = data + dataLen;
			}
			throw new ZipException("invalid CEN header (missing zip64 extra field)");
		}

		private static long nextLong(byte[] b, int at, int limit) throws ZipException {
			if (at + 8 > limit) {
				throw new ZipException("invalid CEN header (bad zip64 extra field)");
			}
			return ll(b, at);
		}

		private int kindFor(int method, boolean directory, int flag) throws ZipException {
			int kind = (method << KIND_METHOD_SHIFT) | (directory ? KIND_DIRECTORY_BIT : 0) | flag;
			for (int k = 1; k < kindCount; k++) {
				if (kinds[k] == kind) {
					return k;
				}
			}
			if (kindCount == MAX_KINDS) {
				throw new ZipException("too many distinct entry kinds");
			}
			kinds[kindCount] = kind;
			return kindCount++;
		}

		/*
		 * Writes the normalized name into scratch and returns its length: leading slash, no
		 * trailing slash, repeated slashes collapsed. An empty or all-slash name is the root.
		 */
		private int normalizeInto(byte[] src, int off, int len) {
			if (scratch.length < len + 1) {
				scratch = new byte[len + 1];
			}
			int out = 0;
			byte prev = 0;
			for (int k = off; k < off + len; k++) {
				byte c = src[k];
				if (c == '/' && prev == '/') {
					continue;
				}
				if (out == 0 && c != '/') {
					scratch[out++] = '/';
				}
				scratch[out++] = c;
				prev = c;
			}
			if (out == 0) {
				scratch[out++] = '/';
			}
			if (out > 1 && scratch[out - 1] == '/') {
				out--;
			}
			return out;
		}

		private int find(byte[] key, int off, int len) {
			return lookup(table, names, nameOffsets, key, off, len);
		}

		/* Creates an entry for key[0..len), plus any missing ancestors, and links it to its parent. */
		private int create(byte[] key, int len) {
			int i = append(key, 0, len);
			int slash = len - 1;
			while (key[slash] != '/') {
				slash--;
			}
			int parent = slash == 0 ? 0 : find(key, 0, slash);
			if (parent < 0) {
				parent = create(key, slash);
			}
			nextSiblings[i] = firstChildren[parent];
			firstChildren[parent] = i;
			return i;
		}

		private int append(byte[] src, int off, int len) {
			if (count == capacity) {
				grow();
			}
			int i = count++;
			if (namesUsed + len > names.length) {
				names = Arrays.copyOf(names, Math.max(names.length * 2, namesUsed + len));
			}
			System.arraycopy(src, off, names, namesUsed, len);
			nameOffsets[i] = namesUsed;
			namesUsed += len;
			nameOffsets[i + 1] = namesUsed;
			firstChildren[i] = -1;
			nextSiblings[i] = -1;
			if (count * 4L > table.length * 3L) {
				rehash();
			}
			insert(table, names, nameOffsets, i);
			return i;
		}

		private void grow() {
			capacity = capacity + (capacity >> 1) + 8;
			nameOffsets = Arrays.copyOf(nameOffsets, capacity + 1);
			firstChildren = Arrays.copyOf(firstChildren, capacity);
			nextSiblings = Arrays.copyOf(nextSiblings, capacity);
			kindIndex = Arrays.copyOf(kindIndex, capacity);
			crcs = Arrays.copyOf(crcs, capacity);
			compressedSizes = Arrays.copyOf(compressedSizes, capacity);
			sizes = Arrays.copyOf(sizes, capacity);
			offsets = Arrays.copyOf(offsets, capacity);
			dosTimes = Arrays.copyOf(dosTimes, capacity);
		}

		/* Called with the new entry's names and offsets in place but not yet inserted. */
		private void rehash() {
			table = new int[table.length * 2];
			for (int i = 0; i < count - 1; i++) {
				insert(table, names, nameOffsets, i);
			}
		}
	}
}
