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

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.itsaky.androidide.javac.config.JavacConfigProvider;
import com.itsaky.androidide.javac.services.fs.AndroidFsProviderImpl;
import com.itsaky.androidide.javac.services.fs.JarPackageProviderImpl;
import com.itsaky.androidide.projects.api.AndroidModule;
import com.itsaky.androidide.projects.api.ModuleProject;
import com.itsaky.androidide.projects.api.TestScope;
import com.itsaky.androidide.projects.models.AndroidProjectExtsKt;
import com.itsaky.androidide.projects.util.StringSearch;
import com.itsaky.androidide.utils.ClassTrie;
import com.itsaky.androidide.utils.Environment;
import com.itsaky.androidide.utils.SourceClassTrie;
import com.itsaky.androidide.zipfs2.JarPackageProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import jdkx.tools.DiagnosticListener;
import jdkx.tools.FileObject;
import jdkx.tools.ForwardingJavaFileManager;
import jdkx.tools.JavaFileObject;
import jdkx.tools.StandardLocation;
import openjdk.tools.javac.file.CacheFSInfo;
import openjdk.tools.javac.file.JavacFileManager;
import openjdk.tools.javac.util.Context;
import openjdk.tools.javac.util.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SourceFileManager extends ForwardingJavaFileManager<JavacFileManager> {

	public static final SourceFileManager NO_MODULE;
	private static final Logger LOG;
	// Keyed by scope as well as module: a shared entry would give main code the test classpath, or tests main's.
	private static final Map<Key, SourceFileManager> cachedFileManagers = new ConcurrentHashMap<>();

	static {
		// Initialize LOG first to prevent NPE
		LOG = LoggerFactory.getLogger(SourceFileManager.class);
		NO_MODULE = new SourceFileManager(null, null);
	}

	public static void clearCache() {
		for (final SourceFileManager fileManager : cachedFileManagers.values()) {
			try {
				fileManager.close();
			} catch (IOException e) {
				LOG.warn("Failed to close SourceFileManager", e);
			}
		}

		cachedFileManagers.clear();
	}

	public static SourceFileManager forModule(@NonNull ModuleProject project) {
		return forModule(project, null);
	}

	/** The file manager for {@code scope}'s tests of {@code project}, or for its main sources when {@code scope} is null. */
	public static SourceFileManager forModule(@NonNull ModuleProject project, @Nullable TestScope scope) {
		Objects.requireNonNull(project);
		return cachedFileManagers.computeIfAbsent(new Key(project, scope), SourceFileManager::createForModule);
	}

	/* Mirrors JavacTool.getStandardFileManager, plus the shared jar package provider. It must be in the context before the file manager is built, because the classpath archive containers are created in the constructor and read the provider only once. */
	static JavacFileManager createDelegateFileManager() {
		final Context context = new Context();
		context.put(Locale.class, Locale.getDefault());
		context.put(DiagnosticListener.class, (DiagnosticListener<JavaFileObject>) msg -> LOG.debug(String.valueOf(msg)));
		context.put(Log.errKey, new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true));
		CacheFSInfo.preRegister(context);
		context.put(JarPackageProvider.class, JarPackageProviderImpl.INSTANCE);
		return new JavacFileManager(context, true, StandardCharsets.UTF_8);
	}

	private static SourceFileManager createForModule(@NonNull Key key) {
		LOG.info("Creating source file manager instance for module: {}, test scope: {}", key.module(), key.scope());
		return new SourceFileManager(key.module(), key.scope());
	}

	private final ModuleProject module;

	@Nullable
	private final TestScope scope;

	private SourceFileManager(final ModuleProject module, @Nullable final TestScope scope) {
		super(createDelegateFileManager());
		this.module = module;
		this.scope = scope;

		AndroidFsProviderImpl.INSTANCE.init();

		if (module == null) {
			return;
		}

		// Must be set before setting classpaths
		System.setProperty(JavacConfigProvider.PROP_ANDROIDIDE_JAVA_HOME,
				Environment.JAVA_HOME.getAbsolutePath());

		final Set<File> sourcePath = scope == null
				? module.getCompileSourceDirectories()
				: module.getTestCompileSourceDirectories(scope);
		setLocationLogError(StandardLocation.SOURCE_PATH, sourcePath);
		setLocationLogError(StandardLocation.CLASS_PATH, configureClasspaths(module));
		listLocations(EnumSet.of(StandardLocation.CLASS_PATH, StandardLocation.PLATFORM_CLASS_PATH));
	}

	@Override
	public boolean contains(Location location, FileObject file) throws IOException {
		if (location == StandardLocation.SOURCE_PATH) {
			SourceFileObject source = (SourceFileObject) file;
			return module.javaSourceClasses(scope).findSource(source.path) != null;
		} else {
			return super.contains(location, file);
		}
	}

	@Override
	public FileObject getFileForInput(Location location, String packageName, String relativeName)
			throws IOException {
		if (location == StandardLocation.SOURCE_PATH) {
			return null;
		}
		return super.getFileForInput(location, packageName, relativeName);
	}

	@Override
	public JavaFileObject getJavaFileForInput(
			Location location, String className, JavaFileObject.Kind kind) throws IOException {
		if (location == StandardLocation.SOURCE_PATH) {
			String packageName = StringSearch.mostName(className);
			String simpleClassName = StringSearch.lastName(className);
			List<SourceClassTrie.SourceNode> classes = module != null ? module.listClassesFromSourceDirs(packageName, scope) : emptyList();
			for (SourceClassTrie.SourceNode node : classes) {
				final Path path = node.getFile();
				if (path.getFileName().toString().equals(simpleClassName + kind.extension)) {
					if (!Files.exists(path))
						return null;

					return new SourceFileObject(path);
				}
			}
			// Fall through to disk in case we have .jar or .zip files on the source path
		}
		return super.getJavaFileForInput(location, className, kind);
	}

	@Override
	public boolean hasLocation(Location location) {
		return location == StandardLocation.SOURCE_PATH || super.hasLocation(location);
	}

	@Override
	public String inferBinaryName(Location location, JavaFileObject file) {
		if (location == StandardLocation.SOURCE_PATH) {
			SourceFileObject source = (SourceFileObject) file;
			String packageName = packageNameOrEmpty(source.path);
			String className = removeExtension(source.path.getFileName().toString());
			if (!packageName.isEmpty()) {
				className = packageName + "." + className;
			}
			return className;
		}

		return super.inferBinaryName(location, file);
	}

	@Override
	public Iterable<JavaFileObject> list(
			Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
			throws IOException {
		if (location == StandardLocation.SOURCE_PATH) {
			if (this.module == null) {
				return emptyList();
			}

			final ClassTrie.Node node = module.javaSourceClasses(scope).findNode(packageName);
			if (node == null || node.isClass()) {
				return emptyList();
			}

			final Stream<ClassTrie.Node> nodes;
			if (recurse) {
				nodes = node.allClassNodes().stream();
			} else {
				nodes = node.getChildren().values().stream();
			}

			final Stream<JavaFileObject> stream = nodes
					.filter(it -> it instanceof SourceClassTrie.SourceNode)
					.map(it -> asJavaFileObject((SourceClassTrie.SourceNode) it))
					.filter(Objects::nonNull);

			return stream::iterator;
		}

		return super.list(location, packageName, kinds, recurse);
	}

	public void setContext(final Context context) {
		fileManager.setContext(context);
	}

	public void setLocationLogError(Location location, Iterable<File> searchPath) {
		try {
			fileManager.setLocation(location, searchPath);
		} catch (IOException e) {
			LOG.error("Unable to set location", e);
		}
	}

	private JavaFileObject asJavaFileObject(SourceClassTrie.SourceNode node) {
		final Path path = node.getFile();
		if (!Files.exists(path))
			return null;

		// TODO erase method bodies of files that are not open
		return new SourceFileObject(node.getFile());
	}

	@NonNull
	private Set<File> configureClasspaths(final ModuleProject module) {
		if (module == null) {
			return emptySet();
		}

		if (module instanceof AndroidModule androidModule) {
			setLocationLogError(StandardLocation.PLATFORM_CLASS_PATH, AndroidProjectExtsKt.getBootClassPaths(androidModule));
		}

		return scope == null ? module.getCompileClasspaths() : module.getTestCompileClasspaths(scope, false);
	}

	private void listLocations(final EnumSet<StandardLocation> locations) {
		for (StandardLocation location : locations) {
			try {
				fileManager.cacheLocation(location);
			} catch (Exception e) {
				// Ignored
				LOG.debug("Failed to list location: {}", location, e);
			}
		}
	}

	private String packageNameOrEmpty(Path file) {
		return this.module != null ? module.packageNameOrEmpty(file, scope) : "";
	}

	private String removeExtension(String fileName) {
		int lastDot = fileName.lastIndexOf(".");
		return (lastDot == -1 ? fileName : fileName.substring(0, lastDot));
	}

	private record Key(ModuleProject module, @Nullable TestScope scope) {}
}
