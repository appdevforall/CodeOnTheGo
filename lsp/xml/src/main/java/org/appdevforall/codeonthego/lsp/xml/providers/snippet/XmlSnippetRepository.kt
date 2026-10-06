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

package org.appdevforall.codeonthego.lsp.xml.providers.snippet

import org.appdevforall.codeonthego.lsp.snippets.ISnippet
import org.appdevforall.codeonthego.lsp.snippets.SnippetLanguage
import org.appdevforall.codeonthego.lsp.snippets.SnippetRegistry

object XmlSnippetRepository {
	val snippets: Map<IXmlSnippetScope, List<ISnippet>>
		get() =
			XML_SNIPPET_SCOPES.associateWith { scope ->
				SnippetRegistry.getSnippets(SnippetLanguage.XML.id, scope.filename)
			}

	fun init() {
		SnippetRegistry.initBuiltIn(SnippetLanguage.XML.id, XML_SNIPPET_SCOPES)
	}
}
