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

package com.itsaky.androidide.lsp.xml.providers.completion.layout

import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup

/**
 * The package and class children a layout tag completer reads from a module's compile classpath.
 *
 * Narrows [ModuleClasspathLookup] to what the completers need, so a test can supply a fake without
 * a real module or a registered index.
 */
internal interface ClasspathLookup {
	/** Returns the direct subpackages of [packageName], then its top-level classes. */
	fun children(packageName: String): List<ModuleClasspathLookup.Child>

	/** Returns whether a top-level class of the classpath is in the package [name] or a subpackage. */
	fun isPackage(name: String): Boolean

	/** Returns whether [qualifiedName] names a top-level class of the classpath. */
	fun isClass(qualifiedName: String): Boolean
}

/** Builds the classpath lookup for [module] from the indexes currently registered for its project. */
internal fun classpathLookupOf(module: ModuleProject): ClasspathLookup {
	val lookup =
		ModuleClasspathLookup.of(module, ProjectManagerImpl.getInstance().indexingServiceManager.registry)
	return object : ClasspathLookup {
		override fun children(packageName: String) = lookup.children(packageName)

		override fun isPackage(name: String) = lookup.isPackage(name)

		override fun isClass(qualifiedName: String) = lookup.isClass(qualifiedName)
	}
}
