package com.itsaky.androidide.treesitter.javascript;

import com.itsaky.androidide.treesitter.TSLanguage;
import com.itsaky.androidide.treesitter.TSLanguageCache;

/**
 * The tree-sitter JavaScript grammar, the same shape as the grammar classes android-tree-sitter publishes (e.g. {@code TSLanguageJson}). It publishes none for JavaScript: see this module's README.
 */
public final class TSLanguageJavascript {

	static {
		System.loadLibrary("tree-sitter-javascript");
	}

	/** Returns the JavaScript language, created once and then cached. */
	public static TSLanguage getInstance() {
		TSLanguage language = TSLanguageCache.get("javascript");
		if (language != null) {
			return language;
		}

		language = TSLanguage.create("javascript", Native.getInstance());
		TSLanguageCache.cache("javascript", language);
		return language;
	}

	private TSLanguageJavascript() {
		throw new UnsupportedOperationException();
	}

	public static class Native {
		public static native long getInstance();
	}
}
