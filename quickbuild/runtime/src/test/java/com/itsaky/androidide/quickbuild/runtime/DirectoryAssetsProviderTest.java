package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The containment rule of the API 30+ assets override. The framework resolves the paths passed to {@link DirectoryAssetsProvider#loadAssetFd} out of resource tables this process does not control, so refusing one that escapes the override directory is a security boundary, not a tidiness check.
 */
class DirectoryAssetsProviderTest {

	@TempDir
	Path tempDir;

	@Test
	void aDotDotEscapeIsRefused() {
		File root = root();

		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(new File(root, "assets/../../secret.json")))
				.isFalse();
	}

	@Test
	void aDotDotThatResolvesBackInsideIsServable() {
		File root = root();

		// Textually suspicious, canonically fine: the rule is about where the path lands,
		// not about whether it spells "..".
		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(new File(root, "assets/../assets/levels.json")))
				.isTrue();
	}

	@Test
	void aPathOutsideTheRootIsRefused() {
		File root = root();

		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(new File(tempDir.toFile(), "secret.json")))
				.isFalse();
	}

	@Test
	void aPathThatCannotBeCanonicalizedIsRefused() {
		File root = root();

		// An embedded NUL makes getCanonicalPath throw rather than answer. A path this
		// process cannot resolve is one it cannot prove is contained, so it must not serve
		// it - refusing is the safe direction, and the framework falls through.
		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(new File(root, "assets/le\0vels.json")))
				.isFalse();
	}

	@Test
	void aPathUnderTheRootIsServable() {
		File root = root();

		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(new File(root, "assets/data/levels.json")))
				.isTrue();
	}

	@Test
	void aRootThatCannotBeCanonicalizedRefusesEveryLookup() {
		// The root is resolved once now, in the constructor, so its failure has to be
		// carried rather than rediscovered per lookup - and it must refuse, not admit.
		// Same direction as the per-path rule: a root this process cannot resolve is one
		// it cannot prove anything is inside of.
		DirectoryAssetsProvider provider = new DirectoryAssetsProvider(
				new File(tempDir.toFile(), "over\0ride"));

		assertThat(provider.isWithinRoot(new File(tempDir.toFile(), "override/assets/levels.json")))
				.isFalse();
		assertThat(provider.loadAssetFd("assets/data/levels.json", 0)).isNull();
	}

	@Test
	void aSiblingSharingTheRootsNamePrefixIsRefused() {
		File root = root();

		// Why the rule appends a separator before comparing: "/tmp/x/overrideEvil" starts
		// with "/tmp/x/override" as text while being an unrelated directory.
		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(new File(tempDir.toFile(), "overrideEvil/secret.json")))
				.isFalse();
	}

	@Test
	void aSymlinkOutOfTheRootIsRefused() throws IOException {
		File root = root();
		File outside = new File(tempDir.toFile(), "outside");
		assertThat(outside.mkdirs()).isTrue();
		Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());

		// Canonicalization is what catches this: the path is textually under the root and
		// resolves outside it.
		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(new File(root, "link/secret.json")))
				.isFalse();
	}

	@Test
	void loadAssetFdFallsThroughForAFileThisOverrideDoesNotCarry() {
		DirectoryAssetsProvider provider = new DirectoryAssetsProvider(root());

		// Null is the fall-through to the next provider and finally the baked-in APK, which
		// is what makes the override additive.
		assertThat(provider.loadAssetFd("assets/data/absent.json", 0)).isNull();
	}

	@Test
	void loadAssetFdRefusesAnEscapingPathEvenWhenItResolvesToARealFile() throws IOException {
		File root = root();
		File secret = new File(tempDir.toFile(), "secret.json");
		Files.write(secret.toPath(), new byte[]{'x'});

		// The file exists and is readable, so null can only come from the containment
		// check - which is the point: this is the guard wired into the framework hook.
		DirectoryAssetsProvider provider = new DirectoryAssetsProvider(root);
		assertThat(provider.loadAssetFd("assets/../../secret.json", 0)).isNull();
	}

	@Test
	void theRootItselfIsNotInsideItself() {
		File root = root();

		assertThat(new DirectoryAssetsProvider(root).isWithinRoot(root)).isFalse();
	}

	private File root() {
		File root = new File(tempDir.toFile(), "override");
		assertThat(root.mkdirs()).isTrue();
		return root;
	}
}
