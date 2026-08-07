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

import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.api.Workspace
import java.nio.file.Path

/**
 * Bridge to the isolated javac session, loaded lazily via `DexClassLoader` on the first
 * real `.java`-file interaction (see `JavaCompilerLoader`) instead of being always resident
 * in the main app dex. Exposes the LSP operations directly rather than a `getCompiler()`
 * accessor, since the underlying `JavaCompilerService`/`Provider` types live entirely on
 * the isolated side of the classloader boundary.
 */
interface IJavaCompilerSession : AutoCloseable {
	/**
	 * Runs the deferred javac project reset for [workspace]: destroys the no-module and
	 * per-module compilers, clears file-manager and R.jar caches, and re-caches classpath
	 * locations for every submodule. Mirrors what `setupWithProject` used to do eagerly.
	 */
	fun resetProject(workspace: Workspace)

	/** Registers the Java code-actions menu. Deferred here since it needs classes from the isolated dex. */
	fun registerCodeActions()

	/**
	 * Removes this session's code actions from the shared editor actions menu. Call this on
	 * shutdown -- otherwise a dead session's action objects (bound to a now-closed
	 * `DexClassLoader`) stay wired into the app-wide menu and can later execute against a
	 * different session's data, throwing `ClassCastException` on same-named-but-differently
	 * -loaded classes.
	 */
	fun unregisterCodeActions()

	/** Destroys per-module compilers, e.g. after a completion failure. Does not close the session. */
	fun destroyCompilers()

	fun complete(params: CompletionParams): CompletionResult

	suspend fun findReferences(params: ReferenceParams): ReferenceResult

	suspend fun findDefinition(params: DefinitionParams): DefinitionResult

	suspend fun expandSelection(params: ExpandSelectionParams): Range

	suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp

	suspend fun analyze(file: Path): DiagnosticResult

	fun onContentChange(event: DocumentChangeEvent)
}
