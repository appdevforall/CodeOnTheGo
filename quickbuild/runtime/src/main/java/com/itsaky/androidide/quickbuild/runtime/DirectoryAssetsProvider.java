package com.itsaky.androidide.quickbuild.runtime;

import android.annotation.TargetApi;
import android.content.res.AssetFileDescriptor;
import android.content.res.loader.AssetsProvider;
import android.os.ParcelFileDescriptor;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Serves a directory laid out as an APK root through the API 30+ {@link AssetsProvider} hook.
 *
 * The framework has such a provider but never made it public API - only the interface is - so this is the minimal open-coded equivalent. Lookups arrive with full APK-relative paths ({@code assets/...}), which is why {@link AssetExtractor} extracts under an {@code assets/} subdirectory.
 *
 * A missing file returns null, which falls the lookup through to the next provider and finally the baked-in APK - that fall-through is what makes the override additive: it can add and replace assets but never hide one.
 */
@TargetApi(30)
final class DirectoryAssetsProvider implements AssetsProvider, Closeable {

	private final File root;

	/**
	 * The root's canonical path with a trailing separator, resolved once: {@code root} is final, so its canonical form cannot change over the provider's lifetime. Null when the root could not be canonicalized, which refuses every lookup.
	 */
	private final String rootPrefix;

	/**
	 * @param root
	 *            the directory to serve, laid out as an APK root (asset files under {@code assets/})
	 */
	DirectoryAssetsProvider(File root) {
		this.root = root;
		String prefix;
		try {
			prefix = root.getCanonicalPath() + File.separator;
		} catch (IOException unresolvableRoot) {
			prefix = null;
		}
		this.rootPrefix = prefix;
	}

	/** Nothing held open between lookups; here so {@link ResourceStore} can treat providers uniformly. */
	@Override
	public void close() {}

	/**
	 * Opens one asset for the framework.
	 *
	 * @param path
	 *            the APK-relative path the framework resolved, such as {@code assets/data/levels.json}
	 * @param accessMode
	 *            ignored; the descriptor is read-only regardless
	 * @return a read-only descriptor over the file, or null when this override does not carry it or the path would escape {@link #root}
	 */
	@Override
	public AssetFileDescriptor loadAssetFd(String path, int accessMode) {
		File candidate = new File(root, path);
		// Same containment rule as AssetExtractor: the path arrives from outside
		// this process's control and must not resolve outside the override dir.
		if (!isWithinRoot(candidate)) {
			return null;
		}
		if (!candidate.isFile()) {
			return null;
		}
		try {
			ParcelFileDescriptor fd = ParcelFileDescriptor.open(
					candidate, ParcelFileDescriptor.MODE_READ_ONLY);
			// Size from the descriptor, not a second stat of the path: open() pinned an inode,
			// and an extraction renaming the file in between would otherwise pair the old
			// inode with the new file's length - a short read, or a read past EOF.
			return new AssetFileDescriptor(fd, 0, fd.getStatSize());
		} catch (FileNotFoundException error) {
			// Raced by a concurrent clear; absent and unreadable look the same to the
			// framework, which falls through to the baked-in copy.
			return null;
		}
	}

	/**
	 * Whether {@code candidate} resolves strictly inside the served root.
	 *
	 * Both sides are canonicalized, so {@code ..} segments and symlinks resolve before the comparison rather than being compared as text. The root's half is resolved in the constructor instead of here, because this runs for every asset the app opens and this provider sits ahead of the baked-in APK in the loader's list. The trailing separator is what stops a sibling whose name merely starts with the root's - {@code /a/rootEvil} against root {@code /a/root} - and it also excludes the root itself.
	 *
	 * @param candidate
	 *            a path resolved against the root
	 * @return true when the candidate may be served; false when it escapes, or when either path could not be canonicalized - unresolvable counts as outside, since a path this process cannot resolve is one it must not serve
	 */
	boolean isWithinRoot(File candidate) {
		if (rootPrefix == null) {
			return false;
		}
		try {
			return candidate.getCanonicalPath().startsWith(rootPrefix);
		} catch (IOException error) {
			return false;
		}
	}
}
