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

package com.itsaky.androidide.tooling.api;

/**
 * Configuration options for the Tooling API.
 *
 * @author Akash Yadav
 */
public final class ToolingConfig {

  /**
   * Whether to inject the JDWP library directory into the target application.
   */
  public static final String PROP_JDWP_INJECT = "ide.property.jdwp.inject";

  /**
   * The directory where the JDWP library is located. The direct children of this directory must be
   * architecture-specific directories, which should contain the shared library (similar to how `jniLibs`
   * directory works in Android projects).
   */
  public static final String PROP_JDWP_LIBDIR = "ide.property.jdwp.libdir";

  /**
   * The name of the JDWP library, without file extension or `lib` prefix.
   * Defaults to {@link #JDWP_LIBNAME_DEFAULT}.
   */
  public static final String PROP_JDWP_LIBNAME = "ide.property.jdwp.libname";

  /**
   * The default value for {@link #PROP_JDWP_LIBNAME}.
   */
  public static final String JDWP_LIBNAME_DEFAULT = "jdwp";

  /**
   * The options to pass to the JDWP library.
   */
  public static final String PROP_JDWP_OPTIONS = "ide.property.jdwp.options";

  /**
   * The default value for {@link #PROP_JDWP_OPTIONS}.
   */
  public static final String JDWP_OPTIONS_DEFAULT = "suspend=n,server=y,transport=dt_socket";

  private ToolingConfig() {
    throw new UnsupportedOperationException("This class cannot be instantiated.");
  }
}
