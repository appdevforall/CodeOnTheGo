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

package com.itsaky.androidide.lsp.java.providers.completion

import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.java.compiler.CompileTask
import com.itsaky.androidide.lsp.java.compiler.JavaCompilerService
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.MatchLevel.CASE_SENSITIVE_EQUAL
import com.itsaky.androidide.lsp.models.MatchLevel.NO_MATCH
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.util.BootClasspathProvider
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ElementKind.ANNOTATION_TYPE
import jdkx.lang.model.element.ElementKind.CLASS
import jdkx.lang.model.element.ElementKind.CONSTRUCTOR
import jdkx.lang.model.element.ElementKind.ENUM
import jdkx.lang.model.element.ElementKind.ENUM_CONSTANT
import jdkx.lang.model.element.ElementKind.FIELD
import jdkx.lang.model.element.ElementKind.INSTANCE_INIT
import jdkx.lang.model.element.ElementKind.INTERFACE
import jdkx.lang.model.element.ElementKind.METHOD
import jdkx.lang.model.element.ElementKind.STATIC_INIT
import jdkx.lang.model.element.Modifier.STATIC
import jdkx.lang.model.element.TypeElement
import openjdk.source.util.TreePath
import openjdk.tools.javac.api.JavacTrees
import openjdk.tools.javac.code.Symbol.MethodSymbol
import openjdk.tools.javac.model.JavacTypes
import openjdk.tools.javac.tree.JCTree.JCImport
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child
import java.nio.file.Path

/**
 * Provides completions for imports.
 *
 * @author Akash Yadav
 */
class ImportCompletionProvider(
	completingFile: Path,
	cursor: Long,
	compiler: JavaCompilerService,
	settings: IServerSettings,
) : IJavaCompletionProvider(cursor, completingFile, compiler, settings) {
	lateinit var importPath: String

	override fun doComplete(
		task: CompileTask,
		path: TreePath,
		partial: String,
		endsWithParen: Boolean,
	): CompletionResult {
		val importTree = path.leaf
		if (importTree !is JCImport) {
			return CompletionResult.EMPTY
		}

		log.info("...complete import for path: {}", importPath)

		val list = mutableListOf<CompletionItem>()

		var pkgName = importPath
		val incomplete: String
		if (!pkgName.contains(".")) {
			pkgName = ""
			incomplete = importPath
		} else if (pkgName.endsWith(".")) {
			pkgName = pkgName.substring(0, pkgName.lastIndex)
			incomplete = ""
		} else {
			incomplete = pkgName.substringAfterLast(delimiter = '.')
			pkgName = pkgName.substringBeforeLast(delimiter = '.')
		}

		abortCompletionIfCancelled()
		run {
			val match = matchLevel("static", incomplete)
			if (match != NO_MATCH && !importTree.isStatic && pkgName.isEmpty()) {
				list.add(keyword("static", incomplete, match))
			}
		}

		abortCompletionIfCancelled()
		val module = compiler.module ?: return CompletionResult(list)

		val children = importPathChildren(module)
		if (pkgName.isBlank()) {
			// User is typing first segment of package name
			// Javac APIs will not work here
			addChildItems(children.of(pkgName), incomplete, list)
			return CompletionResult(list)
		}

		try {
			addChildItems(children.ofPackage(pkgName), incomplete, list)
		} catch (err: RequireMemberCompletionException) {
			// If pkgName is not an existing package name, check if it is a qualified classname
			// A user might be trying to acess members of a member class. So, we keep replacing last '.'
			// until we find a valid qualified name of a class
			if (completeTypeMembers(task, path, pkgName, incomplete, list)) {
				return CompletionResult(list)
			}
		}

		try {
			// pkgName may itself be a class name; its children were already offered by ofPackage above.
			children.requireNotClass(pkgName)
		} catch (e: RequireMemberCompletionException) {
			// User is trying to access members of a class
			if (completeTypeMembers(task, path, pkgName, incomplete, list)) {
				return CompletionResult(list)
			}
		}

		return CompletionResult(list)
	}

	private fun completeTypeMembers(
		task: CompileTask,
		path: TreePath,
		pkgName: String,
		incomplete: String,
		list: MutableList<CompletionItem>,
	): Boolean {
		abortCompletionIfCancelled()
		val elements = task.task.elements
		var typesForPkg: Set<TypeElement> = setOf()
		val maybeInnerName = StringBuilder(pkgName)
		while (true) {
			val types = elements.getAllTypeElements(maybeInnerName)
			if (types.isNotEmpty()) {
				typesForPkg = types
				break
			}

			if (!maybeInnerName.contains(".")) {
				break
			}
			maybeInnerName.setCharAt(maybeInnerName.lastIndexOf('.'), '$')
		}

		abortCompletionIfCancelled()
		if (typesForPkg.isNotEmpty()) {
			// We found a valid class name
			// Add the accessible class items
			for (type in typesForPkg) {
				val result = completeTypeMembers(task, type, path, incomplete)
				if (result.isNotEmpty()) {
					list.addAll(result)
				}
			}
			return true
		}
		return false
	}

	private fun completeTypeMembers(
		task: CompileTask,
		type: TypeElement,
		path: TreePath,
		partial: String,
	): MutableList<CompletionItem> {
		abortCompletionIfCancelled()

		val list = mutableListOf<CompletionItem>()
		val elements = task.task.elements
		val trees = JavacTrees.instance(task.task.context)
		val jcTypes = JavacTypes.instance(task.task.context)
		val scope = trees.getScope(path)
		val isStatic = (path.leaf as JCImport).isStatic
		if (!trees.isAccessible(scope, type)) {
			// Type not accessible
			return list
		}

		val members = elements.getAllMembers(type)
		for (member in members) {
			abortCompletionIfCancelled()
			if (
				member.kind == CONSTRUCTOR || member.kind == STATIC_INIT || member.kind == INSTANCE_INIT
			) {
				continue
			}

			val match = matchLevel(member.simpleName, partial)
			if (match == NO_MATCH) {
				continue
			}

			if (isType(member)) {
				list.add(classItem(member.simpleName.toString(), match))
				continue
			}

			if (!isStatic) {
				continue
			}

			val mods = member.modifiers
			if (!mods.contains(STATIC)) {
				continue
			}

			if (!trees.isAccessible(scope, member, jcTypes.getDeclaredType(type))) {
				continue
			}

			if (member.kind == METHOD) {
				list.add(method(task, listOf(member as MethodSymbol), false, match, partial))
				continue
			}

			if (member.kind == FIELD || member.kind == ENUM_CONSTANT) {
				list.add(item(task, member, match))
			}
		}

		return list
	}

	private fun importPathChildren(module: ModuleProject) =
		ImportPathChildren(module.compileJavaSourceClasses, compiler.classpathPackages(), BootClasspathProvider.getAllEntries())

	private fun addChildItems(
		children: Sequence<Child>,
		incomplete: String,
		list: MutableList<CompletionItem>,
	) {
		for (child in children) {
			abortCompletionIfCancelled()
			val match =
				if (incomplete.isEmpty()) {
					CASE_SENSITIVE_EQUAL
				} else {
					matchLevel(child.name, incomplete)
				}

			if (match == NO_MATCH) {
				continue
			}

			if (child.isClass) {
				list.add(classItem(child.qualifiedName, match))
			} else {
				list.add(packageItem(child.qualifiedName, match))
			}
		}
	}

	internal fun isType(element: Element): Boolean = isType(element.kind)

	internal fun isType(kind: ElementKind): Boolean = kind == ANNOTATION_TYPE || kind == CLASS || kind == INTERFACE || kind == ENUM

	/**
	 * Internal exception to indicate that members of a class must be completed. This is thrown and
	 * caught internally when completing imports.
	 */
	internal class RequireMemberCompletionException : IllegalStateException()
}
