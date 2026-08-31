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
package com.itsaky.androidide.lsp.java.api

import com.itsaky.androidide.projects.api.Workspace

/**
 * Entry point loaded by name (reflection) from the carrier APK's `DexClassLoader` -- see
 * `JavaCompilerLoader`. Implemented by `JavaCompilerSessionFactoryImpl` in the isolated
 * `lsp:java-compiler-impl` module, which must expose a public no-arg constructor for this
 * to work.
 */
interface IJavaCompilerSessionFactory {
	fun create(workspace: Workspace): IJavaCompilerSession
}
