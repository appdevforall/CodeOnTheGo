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

package org.appdevforall.codeonthego.lsp.xml.providers.completion.layout

import com.android.aaptcompiler.extractPathData
import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.lookup.Lookup
import org.appdevforall.codeonthego.lsp.api.ICompletionProvider
import org.appdevforall.codeonthego.lsp.models.CompletionItem
import org.appdevforall.codeonthego.lsp.models.CompletionParams
import org.appdevforall.codeonthego.lsp.models.CompletionResult
import org.appdevforall.codeonthego.lsp.xml.utils.XmlUtils.NodeType.TAG
import org.appdevforall.codeonthego.models.Position
import org.appdevforall.codeonthego.progress.ICancelChecker
import org.appdevforall.codeonthego.projects.api.ModuleProject
import org.appdevforall.codeonthego.xml.internal.widgets.DefaultWidgetTable
import org.appdevforall.codeonthego.xml.widgets.WidgetTable
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** [SimpleTagCompleter] reads root package/class names from the injected [ClasspathLookup]. */
@RunWith(RobolectricTestRunner::class)
class SimpleTagCompleterTest {
	@get:Rule
	val temp = TemporaryFolder()

	@Before
	fun setup() {
		Lookup.getDefault().update(WidgetTable.COMPLETION_LOOKUP_KEY, DefaultWidgetTable())
	}

	@Test
	fun `root package names from the lookup are offered`() {
		val fake =
			FakeClasspathLookup(
				childrenByPackage = mapOf("" to listOf(Child("com", "com", isClass = false))),
			)

		val items = complete(fake, prefix = "")

		assertThat(items.map { it.detail }).contains("com")
	}

	@Test
	fun `a package only the index holds is offered`() {
		// Regression: a trie built only from the module's classpath JARs never held this name.
		val fake =
			FakeClasspathLookup(
				childrenByPackage = mapOf("" to listOf(Child("onlyinindex", "onlyinindex", isClass = false))),
			)

		val items = complete(fake, prefix = "")

		assertThat(items.map { it.detail }).contains("onlyinindex")
	}

	private fun complete(
		lookup: ClasspathLookup,
		prefix: String,
	): List<CompletionItem> {
		Lookup.getDefault().update(ModuleProject.COMPLETION_MODULE_KEY, fakeModuleProject())

		val provider =
			object : ICompletionProvider {
				override fun complete(params: CompletionParams): CompletionResult = CompletionResult.EMPTY
			}
		val completer = SimpleTagCompleter(provider) { lookup }

		val layoutDir = temp.newFolder("layout")
		val file = File(layoutDir, "test.xml").apply { writeText("<LinearLayout/>") }
		val params = CompletionParams(Position(0, 0, 1), file.toPath(), ICancelChecker.NOOP)
		val pathData = extractPathData(file)
		val document = DOMParser.getInstance().parse("<LinearLayout/>", "test.xml", URIResolverExtensionManager())

		return completer.complete(params, pathData, document, TAG, prefix).items
	}
}
