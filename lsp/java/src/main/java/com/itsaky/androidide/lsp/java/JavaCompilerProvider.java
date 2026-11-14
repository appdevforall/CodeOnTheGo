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

package com.itsaky.androidide.lsp.java;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.itsaky.androidide.lsp.java.compiler.JavaCompilerService;
import com.itsaky.androidide.projects.IProjectManager;
import com.itsaky.androidide.projects.api.ModuleProject;
import com.itsaky.androidide.projects.api.TestScope;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides {@link JavaCompilerService} instances for different {@link ModuleProject}s.
 *
 * @author Akash Yadav
 */
public class JavaCompilerProvider {
	private static final Logger logger = LoggerFactory.getLogger(JavaCompilerProvider.class);
	private static JavaCompilerProvider sInstance;

	/** The compiler for {@code file}, per {@link #get(ModuleProject, Path)}, or null when no module holds it. */
	@Nullable
	public static JavaCompilerService forFile(@NonNull File file) {
		return forFile(file.toPath());
	}

	/** See {@link #forFile(File)}. */
	@Nullable
	public static JavaCompilerService forFile(@NonNull Path file) {
		final ModuleProject module = IProjectManager.getInstance().findModuleForFile(file, false);
		return module == null ? null : get(module, file);
	}

	/** The compiler for {@code module}'s main sources. Use {@link #get(ModuleProject, Path)} for a file that may be a test. */
	@NonNull
	public static JavaCompilerService get(ModuleProject module) {
		return JavaCompilerProvider.getInstance().forModule(module, null);
	}

	/** See {@link #get(ModuleProject, Path)}. */
	@NonNull
	public static JavaCompilerService get(@NonNull ModuleProject module, @NonNull File file) {
		return get(module, file.toPath());
	}

	/** The compiler for {@code file}: its test scope's if {@code file} is a test source of {@code module}, main's otherwise. */
	@NonNull
	public static JavaCompilerService get(@NonNull ModuleProject module, @NonNull Path file) {
		return JavaCompilerProvider.getInstance().forModule(module, module.testScopeOf(file));
	}

	public static JavaCompilerProvider getInstance() {
		if (sInstance == null) {
			sInstance = new JavaCompilerProvider();
		}

		return sInstance;
	}

	// One compiler per module and test scope: each compiles against its own source path and classpath.
	private final Map<Key, JavaCompilerService> mCompilers = new ConcurrentHashMap<>();

	private JavaCompilerProvider() {}

	// TODO This currently destroys all the compiler instances
	// We must have a method to destroy only the required instance in
	// JavaLanguageServer.handleFailure(LSPFailure)
	public synchronized void destroy() {
		for (final JavaCompilerService compiler : mCompilers.values()) {
			compiler.destroy();
		}
		mCompilers.clear();
	}

	/**
	 * Iterate over all available {@link JavaCompilerService} instances to perform given {@code action} function and return the result of the function.
	 *
	 * @param action
	 *            The function to consume the {@link JavaCompilerService} instances and produce a result. The function can return {@code null} to indicate that no result was produced for the provided element. If the function returns a non-null value, the iteration is stopped and the non-null result is returned. If the function returns {@code null} for all elements, then {@code null} is returned.
	 * @return The result of the action.
	 * @param <T>
	 *            The type of the result produced by the given function.
	 */
	@Nullable
	public synchronized <T> T find(Function<JavaCompilerService, T> action) {
		logger.debug("find from {} compiler services", mCompilers.size());
		for (JavaCompilerService service : mCompilers.values()) {
			final var result = action.apply(service);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	@NonNull
	public synchronized JavaCompilerService forModule(ModuleProject module) {
		return forModule(module, null);
	}

	/** The compiler for {@code scope}'s tests of {@code module}, or for its main sources when {@code scope} is null. */
	@NonNull
	public synchronized JavaCompilerService forModule(ModuleProject module, @Nullable TestScope scope) {
		// A module instance is set to the compiler only in case the project is initialized or
		// this method was called with other module instance.
		final Key key = new Key(module, scope);
		final JavaCompilerService cached = mCompilers.get(key);
		if (cached != null && cached.getModule() != null) {
			return cached;
		}

		final JavaCompilerService newInstance = new JavaCompilerService(module, scope);
		mCompilers.put(key, newInstance);

		return newInstance;
	}

	private record Key(ModuleProject module, @Nullable TestScope scope) {}
}
