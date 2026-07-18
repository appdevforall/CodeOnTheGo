package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JVM-testable half of the API 28/29 shim: the arsc-to-zip wrapping. The reflective addAssetPath / cache flush can only run on a real device.
 */
class LegacyResourceSwapTest {

	private static byte[] readSoleEntry(File zip) throws IOException {
		ZipFile read = new ZipFile(zip);
		try {
			ZipEntry entry = read.getEntry(LegacyResourceSwap.TABLE_ENTRY_NAME);
			assertThat(entry).isNotNull();
			InputStream in = read.getInputStream(entry);
			try {
				return Streams.readFully(in);
			} finally {
				in.close();
			}
		} finally {
			read.close();
		}
	}

	@TempDir
	File tempDir;

	@Test
	void createsMissingDirectories() throws IOException {
		File nested = new File(new File(tempDir, "a"), "b");
		byte[] table = "table".getBytes("UTF-8");

		File zip = LegacyResourceSwap.writeTableZip(new ByteArrayInputStream(table), nested, 0);

		assertThat(zip.isFile()).isTrue();
		assertThat(readSoleEntry(zip)).isEqualTo(table);
	}

	@Test
	void distinctFilePerGeneration() throws IOException {
		byte[] first = "gen one table".getBytes("UTF-8");
		byte[] second = "gen two table - different".getBytes("UTF-8");

		File zipOne = LegacyResourceSwap.writeTableZip(new ByteArrayInputStream(first), tempDir, 1);
		File zipTwo = LegacyResourceSwap.writeTableZip(new ByteArrayInputStream(second), tempDir, 2);

		assertThat(zipOne.getAbsolutePath()).isNotEqualTo(zipTwo.getAbsolutePath());
		assertThat(readSoleEntry(zipOne)).isEqualTo(first);
		assertThat(readSoleEntry(zipTwo)).isEqualTo(second);
	}

	@Test
	void uncreatableDirectoryThrowsInsteadOfSilentlyDropping() throws IOException {
		// A dir path shadowed by an existing FILE cannot be created; the shim must throw
		// (deploy rolls back) rather than lose the resource payload (never-stale).
		File shadow = new File(tempDir, "shadow");
		assertThat(shadow.createNewFile()).isTrue();

		assertThrows(IOException.class, () -> LegacyResourceSwap
				.writeTableZip(new ByteArrayInputStream(new byte[]{1}), shadow, 1));
	}

	@Test
	void wrapsTableAsSingleStoredEntry() throws IOException {
		byte[] table = new byte[64 * 1024];
		new Random(7).nextBytes(table);

		File zip = LegacyResourceSwap.writeTableZip(new ByteArrayInputStream(table), tempDir, 3);

		assertThat(zip.getName()).isEqualTo("gen-3.zip");
		ZipFile read = new ZipFile(zip);
		try {
			List<ZipEntry> entries = new ArrayList<ZipEntry>();
			for (Enumeration<? extends ZipEntry> e = read.entries(); e.hasMoreElements();) {
				entries.add(e.nextElement());
			}
			assertThat(entries).hasSize(1);
			ZipEntry entry = entries.get(0);
			// STORED is load-bearing: the native ResTable mmaps the arsc, which the
			// framework only allows for uncompressed entries.
			assertThat(entry.getName()).isEqualTo(LegacyResourceSwap.TABLE_ENTRY_NAME);
			assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
			assertThat(entry.getSize()).isEqualTo(table.length);
			InputStream in = read.getInputStream(entry);
			try {
				assertThat(Streams.readFully(in)).isEqualTo(table);
			} finally {
				in.close();
			}
		} finally {
			read.close();
		}
	}
}
