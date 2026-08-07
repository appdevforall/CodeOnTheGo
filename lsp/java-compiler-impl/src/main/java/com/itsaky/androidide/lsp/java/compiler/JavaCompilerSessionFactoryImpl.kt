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
package com.itsaky.androidide.lsp.java.compiler

import com.itsaky.androidide.lsp.java.api.IJavaCompilerSession
import com.itsaky.androidide.lsp.java.api.IJavaCompilerSessionFactory
import com.itsaky.androidide.projects.api.Workspace

/**
 * Loaded by name (reflection) via `JavaCompilerLoader` -- must keep a public no-arg
 * constructor. Does not reset the project itself: `JavaLanguageServer.ensureProjectReset()`
 * always calls `resetProject` right after getting a session, whether newly created or
 * already existing (e.g. after a project switch), so resetting here too would be redundant.
 */
class JavaCompilerSessionFactoryImpl : IJavaCompilerSessionFactory {
	override fun create(workspace: Workspace): IJavaCompilerSession = JavaCompilerSessionImpl()
}
