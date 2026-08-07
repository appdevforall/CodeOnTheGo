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

package com.itsaky.androidide.lsp.java.compiler;

import com.google.common.base.MoreObjects;
import com.itsaky.androidide.projects.FileManager;
import com.itsaky.androidide.utils.DocumentUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import jdkx.lang.model.element.Modifier;
import jdkx.lang.model.element.NestingKind;
import jdkx.tools.JavaFileObject;

public class SourceFileObject implements JavaFileObject {
	private static Kind kindFromExtension(String name) {
		for (Kind candidate : Kind.values()) {
			if (name.endsWith(candidate.extension)) {
				return candidate;
			}
		}
		return null;
	}

	// toRealPath() resolves symlinks the same way Files.isSameFile() effectively does; falls back
	// to a plain absolute+normalized path if the file doesn't exist yet (toRealPath requires it).
	private static Path resolveCanonicalPath(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	/** path is the absolute path to this file on disk */
	final Path path;
	/**
	 * Identity key for equals()/hashCode(), separate from {@link #path}: the two paths compared in equals() may be textually different (a symlink, or a relative vs. canonicalized form) yet refer to the same file, so both sides need to hash the same normalized value. Resolved once here rather than via a live {@code Files.isSameFile} check per comparison, which has no hash of its own to key off of.
	 */
	private final Path canonicalPath;

	/** contents is the text in this file, or null if we should use the text in FileStore */
	String contents;

	/** if contents is set, the modified time of contents */
	Instant modified;

	public SourceFileObject(Path path) {
		this(path, null, Instant.EPOCH);
	}

	public SourceFileObject(Path path, String contents, Instant modified) {
		if (!DocumentUtils.isJavaFile(path))
			throw new RuntimeException(path + " is not a java source");
		this.path = path;
		this.canonicalPath = resolveCanonicalPath(path);
		this.contents = contents;
		this.modified = modified;
	}

	@Override
	public boolean delete() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof SourceFileObject)) {
			return false;
		}
		final SourceFileObject that = (SourceFileObject) o;
		return Objects.equals(this.canonicalPath, that.canonicalPath)
				&& Objects.equals(contents, that.contents)
				&& Objects.equals(modified, that.modified);
	}

	@Override
	public Modifier getAccessLevel() {
		return null;
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) {
		if (contents != null) {
			return contents;
		}
		return FileManager.INSTANCE.getDocumentContents(this.path);
	}

	@Override
	public Kind getKind() {
		String name = path.getFileName().toString();
		return kindFromExtension(name);
	}

	@Override
	public long getLastModified() {
		if (contents != null) {
			return modified.toEpochMilli();
		}
		return FileManager.INSTANCE.getLastModified(this.path).toEpochMilli();
	}

	@Override
	public String getName() {
		return path.toString();
	}

	@Override
	public NestingKind getNestingKind() {
		return null;
	}

	@Override
	public int hashCode() {
		return Objects.hash(canonicalPath, contents, modified);
	}

	@Override
	public boolean isNameCompatible(String simpleName, Kind kind) {
		return path.getFileName().toString().equals(simpleName + kind.extension);
	}

	@Override
	public InputStream openInputStream() {
		if (contents != null) {
			byte[] bytes = contents.getBytes();
			return new ByteArrayInputStream(bytes);
		}
		return FileManager.INSTANCE.getInputStream(path);
	}

	@Override
	public OutputStream openOutputStream() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Reader openReader(boolean ignoreEncodingErrors) {
		if (contents != null) {
			return new StringReader(contents);
		}
		return FileManager.INSTANCE.getReader(path);
	}

	@Override
	public Writer openWriter() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this).add("path", this.path.toString()).toString();
	}

	@Override
	public URI toUri() {
		return this.path.toAbsolutePath().toUri();
	}
}
