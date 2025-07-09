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
import com.itsaky.androidide.projects.api.ModuleProject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Provides {@link JavaCompilerService} instances for different {@link ModuleProject}s.
 *
 * @author Akash Yadav
 */
public class JavaCompilerProvider {
  private static final Logger logger = LoggerFactory.getLogger(JavaCompilerProvider.class);
  private static JavaCompilerProvider sInstance;
  private final Map<ModuleProject, JavaCompilerService> mCompilers = new ConcurrentHashMap<>();

  private JavaCompilerProvider() {}

  @NonNull
  public static JavaCompilerService get(ModuleProject module) {
    return JavaCompilerProvider.getInstance().forModule(module);
  }

  public static JavaCompilerProvider getInstance() {
    if (sInstance == null) {
      sInstance = new JavaCompilerProvider();
    }

    return sInstance;
  }

  /**
   * Iterate over all available {@link JavaCompilerService} instances to perform given {@code action}
   * function and return the result of the function.
   *
   * @param action The function to consume the {@link JavaCompilerService} instances and produce a result.
   *               The function can return {@code null} to indicate that no result was produced for the
   *               provided element. If the function returns a non-null value, the iteration is stopped
   *               and the non-null result is returned. If the function returns {@code null} for all
   *               elements, then {@code null} is returned.
   * @return The result of the action.
   * @param <T> The type of the result produced by the given function.
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
    // A module instance is set to the compiler only in case the project is initialized or
    // this method was called with other module instance.
    final JavaCompilerService cached = mCompilers.get(module);
    if (cached != null && cached.getModule() != null) {
      return cached;
    }

    final JavaCompilerService newInstance = new JavaCompilerService(module);
    mCompilers.put(module, newInstance);

    return newInstance;
  }

  // TODO This currently destroys all the compiler instances
  //  We must have a method to destroy only the required instance in
  //  JavaLanguageServer.handleFailure(LSPFailure)
  public synchronized void destroy() {
    for (final JavaCompilerService compiler : mCompilers.values()) {
      compiler.destroy();
    }
    mCompilers.clear();
  }
}
