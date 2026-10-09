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

import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.ModuleProject
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup

/** A hand-built [ClasspathLookup] a test can shape without a real module or index. */
class FakeClasspathLookup(
	private val childrenByPackage: Map<String, List<ModuleClasspathLookup.Child>> = emptyMap(),
	private val packages: Set<String> = emptySet(),
	private val classes: Set<String> = emptySet(),
) : ClasspathLookup {
	override fun children(packageName: String): List<ModuleClasspathLookup.Child> = childrenByPackage[packageName] ?: emptyList()

	override fun isPackage(name: String): Boolean = name in packages

	override fun isClass(qualifiedName: String): Boolean = qualifiedName in classes
}

/**
 * Builds a bare [ModuleProject] a test can register in [com.itsaky.androidide.lookup.Lookup],
 * with no real Gradle sync or disk I/O. Its own classpath is never read: a test that needs one
 * supplies a [ClasspathLookup] fake instead.
 */
fun fakeModuleProject(path: String = ":app"): ModuleProject {
	val delegate =
		GradleModels.GradleProject
			.newBuilder()
			.setName(path.trimStart(':'))
			.setPath(path)
			.setProjectDirPath("/fake$path")
			.setBuildDirPath("/fake$path/build")
			.setBuildScriptPath("/fake$path/build.gradle")
			.setJavaProject(JavaModels.JavaProject.getDefaultInstance())
			.build()
	return JavaModule(delegate)
}
