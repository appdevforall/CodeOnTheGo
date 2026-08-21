package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the extractor's failure paths: unmountable destination dirs and the rename fallback.
 *
 * A POSIX rename cannot replace a directory with a file, so pre-planting a directory where a file entry must land drives the delete-and-retry fallback both ways: an empty dir lets the fallback succeed, a non-empty one makes extraction fail loudly and leave no temp file behind.
 */
class AssetExtractorFailurePathTest {

	private static String readFile(File file) throws IOException {
		FileInputStream in = new FileInputStream(file);
		try {
			return new String(Streams.readFully(in), "UTF-8");
		} finally {
			in.close();
		}
	}

	private static InputStream zipWithEntry(String name, byte[] content) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ZipOutputStream zip = new ZipOutputStream(bytes);
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content);
		zip.closeEntry();
		zip.close();
		return new ByteArrayInputStream(bytes.toByteArray());
	}

	@TempDir
	Path tempDir;

	@Test
	void aDestDirBlockedByAFileThrows() throws IOException {
		File blocked = tempDir.resolve("dest").toFile();
		Files.write(blocked.toPath(), "not a dir".getBytes("UTF-8"));

		IOException error = assertThrows(IOException.class,
				() -> AssetExtractor.extract(zipWithEntry("a.txt", "x".getBytes("UTF-8")),
						blocked));

		assertThat(error).hasMessageThat().contains("cannot create asset dir");
	}

	@Test
	void anEntryParentBlockedByAFileThrows() throws IOException {
		File dest = tempDir.resolve("dest").toFile();
		assertThat(dest.mkdirs()).isTrue();
		Files.write(dest.toPath().resolve("sub"), "not a dir".getBytes("UTF-8"));

		IOException error = assertThrows(IOException.class,
				() -> AssetExtractor.extract(
						zipWithEntry("sub/a.txt", "x".getBytes("UTF-8")), dest));

		assertThat(error).hasMessageThat().contains("cannot create dir");
	}

	@Test
	void anUndeletableTargetFailsLoudlyAndLeavesNoTempFile() throws IOException {
		File dest = tempDir.resolve("dest").toFile();
		File inTheWay = new File(dest, "a.txt");
		// A NON-empty directory: rename over it fails, delete fails, retry fails.
		assertThat(new File(inTheWay, "child").mkdirs()).isTrue();

		IOException error = assertThrows(IOException.class,
				() -> AssetExtractor.extract(
						zipWithEntry("a.txt", "x".getBytes("UTF-8")), dest));

		assertThat(error).hasMessageThat()
				.contains("cannot move extracted asset into place");
		assertThat(new File(dest, "a.txt.qb-tmp").exists()).isFalse();
		// The pre-existing content is untouched.
		assertThat(new File(inTheWay, "child").isDirectory()).isTrue();
	}

	@Test
	void renameFallbackReplacesAnEmptyDirectoryInTheWay() throws IOException {
		File dest = tempDir.resolve("dest").toFile();
		File inTheWay = new File(dest, "a.txt");
		assertThat(inTheWay.mkdirs()).isTrue();

		int count = AssetExtractor.extract(
				zipWithEntry("a.txt", "fresh".getBytes("UTF-8")), dest);

		assertThat(count).isEqualTo(1);
		assertThat(inTheWay.isFile()).isTrue();
		assertThat(readFile(inTheWay)).isEqualTo("fresh");
	}
}
