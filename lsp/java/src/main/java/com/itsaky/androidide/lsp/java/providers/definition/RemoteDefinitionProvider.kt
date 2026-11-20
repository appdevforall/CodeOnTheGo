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

package com.itsaky.androidide.lsp.java.providers.definition

import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.java.compiler.JavaCompilerService
import com.itsaky.androidide.lsp.java.compiler.SourceFileObject
import com.itsaky.androidide.lsp.java.utils.FindHelper
import com.itsaky.androidide.lsp.java.utils.NavigationHelper
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import jdkx.lang.model.element.Element
import jdkx.tools.JavaFileObject
import openjdk.source.util.Trees
import java.nio.file.Path

/**
 * Finds definition of an element in other source locations.
 *
 * @author Akash Yadav
 */
class RemoteDefinitionProvider(
	position: Position,
	completingFile: Path,
	compiler: JavaCompilerService,
	settings: IServerSettings,
	cancelChecker: ICancelChecker,
) : IJavaDefinitionProvider(position, completingFile, compiler, settings, cancelChecker) {
	private lateinit var otherFile: JavaFileObject

	fun setOtherFile(jfo: JavaFileObject): RemoteDefinitionProvider {
		this.otherFile = jfo
		return this
	}

	// The compiler reuses one javac context: a class an earlier request compiled stays known without its
	// tree, so the declaration only has a path when its file is compiled together with this one.
	override fun doFindDefinition(element: Element): List<Location> =
		compiler.compile(listOf(SourceFileObject(file), otherFile)).get { task ->
			val inBatch = NavigationHelper.findElement(task, file, line, column, this) ?: return@get emptyList()
			val path = Trees.instance(task.task).getPath(inBatch)
			if (path == null) {
				log.error("TreePath of element is null. Cannot find definition. Element is {}", inBatch)
				return@get emptyList()
			}
			val name = if (inBatch.simpleName.contentEquals("<init>")) inBatch.enclosingElement.simpleName else inBatch.simpleName
			listOf(FindHelper.location(task, path, name))
		}
}
