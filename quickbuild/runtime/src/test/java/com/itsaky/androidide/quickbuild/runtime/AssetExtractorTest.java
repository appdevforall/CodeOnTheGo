package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetExtractorTest {

	private static String readFile(File file) throws IOException {
		FileInputStream in = new FileInputStream(file);
		try {
			return new String(Streams.readFully(in), "UTF-8");
		} finally {
			in.close();
		}
	}

	private static InputStream zipOf(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ZipOutputStream zip = new ZipOutputStream(bytes);
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			zip.putNextEntry(new ZipEntry(entry.getKey()));
			zip.write(entry.getValue());
			zip.closeEntry();
		}
		zip.close();
		return new ByteArrayInputStream(bytes.toByteArray());
	}

	@TempDir
	Path tempDir;

	@Test
	void cumulativeMergeKeepsEarlierPayloadsFiles() throws IOException {
		File root = tempDir.resolve("assets-root").toFile();
		Map<String, byte[]> first = new LinkedHashMap<String, byte[]>();
		first.put("message.txt", "hello".getBytes("UTF-8"));
		AssetExtractor.extractCumulative(zipOf(first), root, "fp-1");

		Map<String, byte[]> second = new LinkedHashMap<String, byte[]>();
		second.put("data/levels.json", "{}".getBytes("UTF-8"));
		int count = AssetExtractor.extractCumulative(zipOf(second), root, "fp-1");

		// The second payload carries only its own delta; the first one's file must survive.
		File assetsDir = new File(AssetExtractor.currentDir(root), AssetExtractor.ASSETS_SUBDIR);
		assertThat(count).isEqualTo(1);
		assertThat(readFile(new File(assetsDir, "message.txt"))).isEqualTo("hello");
		assertThat(readFile(new File(assetsDir, "data/levels.json"))).isEqualTo("{}");
	}

	@Test
	void cumulativeMergeOverwritesChangedFile() throws IOException {
		File root = tempDir.resolve("assets-root").toFile();
		Map<String, byte[]> first = new LinkedHashMap<String, byte[]>();
		first.put("message.txt", "old".getBytes("UTF-8"));
		AssetExtractor.extractCumulative(zipOf(first), root, "fp-1");

		Map<String, byte[]> second = new LinkedHashMap<String, byte[]>();
		second.put("message.txt", "new".getBytes("UTF-8"));
		AssetExtractor.extractCumulative(zipOf(second), root, "fp-1");

		File assetsDir = new File(AssetExtractor.currentDir(root), AssetExtractor.ASSETS_SUBDIR);
		assertThat(readFile(new File(assetsDir, "message.txt"))).isEqualTo("new");
	}

	@Test
	void cumulativeRejectsZipSlipEntry() throws IOException {
		File root = tempDir.resolve("assets-root").toFile();
		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		entries.put("../../evil.txt", "escaped".getBytes("UTF-8"));

		assertThrows(IOException.class,
				() -> AssetExtractor.extractCumulative(zipOf(entries), root, "fp-1"));

		assertThat(new File(tempDir.toFile(), "evil.txt").exists()).isFalse();
	}

	@Test
	void emptyZipExtractsNothing() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ZipOutputStream zip = new ZipOutputStream(bytes);
		// A zip must contain at least one entry to be written; use a directory-only zip.
		zip.putNextEntry(new ZipEntry("emptydir/"));
		zip.closeEntry();
		zip.close();
		File dest = tempDir.resolve("out").toFile();

		int count = AssetExtractor.extract(new ByteArrayInputStream(bytes.toByteArray()), dest);

		assertThat(count).isEqualTo(0);
	}

	@Test
	void extractsFilesWithNestedDirectories() throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		entries.put("data/levels.json", "{\"level\": 1}".getBytes("UTF-8"));
		entries.put("img/icons/star.png", new byte[]{1, 2, 3});
		entries.put("top.txt", "hello".getBytes("UTF-8"));
		File dest = tempDir.resolve("out").toFile();

		int count = AssetExtractor.extract(zipOf(entries), dest);

		assertThat(count).isEqualTo(3);
		assertThat(readFile(new File(dest, "data/levels.json"))).isEqualTo("{\"level\": 1}");
		assertThat(new File(dest, "img/icons/star.png").length()).isEqualTo(3);
		assertThat(readFile(new File(dest, "top.txt"))).isEqualTo("hello");
	}

	@Test
	void leavesNoTempFilesBehind() throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		entries.put("a/b.txt", "x".getBytes("UTF-8"));
		File dest = tempDir.resolve("out").toFile();

		AssetExtractor.extract(zipOf(entries), dest);

		assertThat(new File(dest, "a").list()).asList().containsExactly("b.txt");
	}

	@Test
	void markerRecordsTheBaselineFingerprint() throws IOException {
		File root = tempDir.resolve("assets-root").toFile();
		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		entries.put("a.txt", "x".getBytes("UTF-8"));

		AssetExtractor.extractCumulative(zipOf(entries), root, "fp-abc");

		// The marker is what a later process compares against; it must hold the exact key.
		assertThat(readFile(new File(root, AssetExtractor.BASELINE_MARKER))).isEqualTo("fp-abc");
	}

	@Test
	void missingMarkerClearsPreexistingDir() throws IOException {
		// A dir with no marker has unknown provenance - never serve it.
		File root = tempDir.resolve("assets-root").toFile();
		File strayDir = new File(AssetExtractor.currentDir(root), AssetExtractor.ASSETS_SUBDIR);
		strayDir.mkdirs();
		java.io.FileOutputStream out = new java.io.FileOutputStream(new File(strayDir, "stray.txt"));
		out.write("unowned".getBytes("UTF-8"));
		out.close();

		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		entries.put("fresh.txt", "owned".getBytes("UTF-8"));
		AssetExtractor.extractCumulative(zipOf(entries), root, "fp-1");

		assertThat(new File(strayDir, "stray.txt").exists()).isFalse();
		assertThat(readFile(new File(strayDir, "fresh.txt"))).isEqualTo("owned");
	}

	@Test
	void nullFingerprintIsRefused() throws IOException {
		File root = tempDir.resolve("assets-root").toFile();
		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		entries.put("a.txt", "x".getBytes("UTF-8"));
		InputStream zip = zipOf(entries);

		assertThrows(IOException.class,
				() -> AssetExtractor.extractCumulative(zip, root, null));

		assertThat(AssetExtractor.currentDir(root).exists()).isFalse();
	}

	@Test
	void overwritesExistingFiles() throws IOException {
		File dest = tempDir.resolve("out").toFile();
		Map<String, byte[]> first = new LinkedHashMap<String, byte[]>();
		first.put("data/config.txt", "old".getBytes("UTF-8"));
		AssetExtractor.extract(zipOf(first), dest);

		Map<String, byte[]> second = new LinkedHashMap<String, byte[]>();
		second.put("data/config.txt", "new".getBytes("UTF-8"));
		AssetExtractor.extract(zipOf(second), dest);

		assertThat(readFile(new File(dest, "data/config.txt"))).isEqualTo("new");
	}

	@Test
	void rebaselineClearsMergedAssets() throws IOException {
		File root = tempDir.resolve("assets-root").toFile();
		Map<String, byte[]> first = new LinkedHashMap<String, byte[]>();
		first.put("stale.txt", "from the old baseline".getBytes("UTF-8"));
		AssetExtractor.extractCumulative(zipOf(first), root, "fp-old");

		Map<String, byte[]> second = new LinkedHashMap<String, byte[]>();
		second.put("fresh.txt", "from the new baseline".getBytes("UTF-8"));
		AssetExtractor.extractCumulative(zipOf(second), root, "fp-new");

		// The proxy rebuild changed the baseline: the old baseline's assets must not survive it.
		File assetsDir = new File(AssetExtractor.currentDir(root), AssetExtractor.ASSETS_SUBDIR);
		assertThat(new File(assetsDir, "stale.txt").exists()).isFalse();
		assertThat(readFile(new File(assetsDir, "fresh.txt"))).isEqualTo("from the new baseline");
	}

	@Test
	void rejectsZipSlipEntry() throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		entries.put("../evil.txt", "escaped".getBytes("UTF-8"));
		File dest = tempDir.resolve("out").toFile();

		assertThrows(IOException.class, () -> AssetExtractor.extract(zipOf(entries), dest));

		assertThat(new File(tempDir.toFile(), "evil.txt").exists()).isFalse();
	}

	@Test
	void skipsDirectoryEntries() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ZipOutputStream zip = new ZipOutputStream(bytes);
		zip.putNextEntry(new ZipEntry("data/"));
		zip.closeEntry();
		zip.putNextEntry(new ZipEntry("data/file.txt"));
		zip.write("content".getBytes("UTF-8"));
		zip.closeEntry();
		zip.close();
		File dest = tempDir.resolve("out").toFile();

		int count = AssetExtractor.extract(new ByteArrayInputStream(bytes.toByteArray()), dest);

		assertThat(count).isEqualTo(1);
		assertThat(readFile(new File(dest, "data/file.txt"))).isEqualTo("content");
	}
}
