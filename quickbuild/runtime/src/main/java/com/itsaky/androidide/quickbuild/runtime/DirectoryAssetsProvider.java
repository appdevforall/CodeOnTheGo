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

	/**
	 * Whether {@code candidate} resolves strictly inside {@code root}.
	 *
	 * Both sides are canonicalized, so {@code ..} segments and symlinks resolve before the comparison rather than being compared as text. The trailing separator is what stops a sibling whose name merely starts with the root's - {@code /a/rootEvil} against root {@code /a/root} - and it also excludes {@code root} itself.
	 *
	 * @param root
	 *            the override directory being served
	 * @param candidate
	 *            a path resolved against it
	 * @return true when the candidate may be served; false when it escapes, or when either path cannot be canonicalized - unresolvable counts as outside, since a path this process cannot resolve is one it must not serve
	 */
	static boolean isWithinRoot(File root, File candidate) {
		try {
			return candidate.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
		} catch (IOException error) {
			return false;
		}
	}

	private final File root;

	/**
	 * @param root
	 *            the directory to serve, laid out as an APK root (asset files under {@code assets/})
	 */
	DirectoryAssetsProvider(File root) {
		this.root = root;
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
		if (!isWithinRoot(root, candidate)) {
			return null;
		}
		if (!candidate.isFile()) {
			return null;
		}
		try {
			ParcelFileDescriptor fd = ParcelFileDescriptor.open(
					candidate, ParcelFileDescriptor.MODE_READ_ONLY);
			return new AssetFileDescriptor(fd, 0, candidate.length());
		} catch (FileNotFoundException error) {
			// Raced by a concurrent clear; absent and unreadable look the same to the
			// framework, which falls through to the baked-in copy.
			return null;
		}
	}
}
