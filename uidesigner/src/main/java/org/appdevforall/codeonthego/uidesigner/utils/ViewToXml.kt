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

package org.appdevforall.codeonthego.uidesigner.utils

import android.content.Context
import org.appdevforall.codeonthego.inflater.IView
import org.appdevforall.codeonthego.inflater.IViewGroup
import org.appdevforall.codeonthego.inflater.internal.ViewGroupImpl
import org.appdevforall.codeonthego.inflater.internal.ViewImpl
import org.appdevforall.codeonthego.lsp.xml.utils.XMLBuilder
import org.appdevforall.codeonthego.tasks.executeAsyncProvideError
import org.appdevforall.codeonthego.tasks.runOnUiThread
import com.itsaky.androidide.uidesigner.R
import org.appdevforall.codeonthego.utils.DialogUtils
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Generates XML code for [IView].
 *
 * @author Akash Yadav
 */
object ViewToXml {
	private val log = LoggerFactory.getLogger(ViewToXml::class.java)

	@JvmStatic
	@JvmOverloads
	fun generateXml(
		context: Context,
		workspace: ViewGroupImpl,
		onGenerated: (String) -> Unit = {},
		onFailure: (result: String?, error: Throwable?) -> Unit,
	) {
		context.apply {
			val future: CompletableFuture<String?> =
				executeAsyncProvideError({
					if (workspace.childCount == 0) {
						throw CompletionException(
							IllegalStateException("No views have been added to workspace"),
						)
					}

					if (workspace.childCount > 1) {
						throw CompletionException(
							IllegalStateException("Invalid view hierarchy. More than one root views found."),
						)
					}

					return@executeAsyncProvideError generateXml(workspace[0] as ViewImpl)
				}) { _, _ -> }

			val progress =
				DialogUtils
					.newProgressDialog(
						this,
						getString(R.string.title_generating_xml),
						getString(R.string.please_wait),
						false,
					) { _, _ ->
						future.cancel(true)
					}.show()

			future.whenComplete { result, error ->
				runOnUiThread {
					progress.dismiss()

					if (result.isNullOrBlank() || error != null) {
						log.error("Unable to generate XML code", error)
						onFailure(result, error)
						return@runOnUiThread
					}

					onGenerated(result)
				}
			}
		}
	}

	@JvmStatic
	fun generateXml(view: ViewImpl): String {
		val builder = XMLBuilder("", System.lineSeparator())
		builder.apply {
			appendXmlProlog()
			linefeed()
			appendView(view)
		}
		return builder.toString()
	}

	private fun XMLBuilder.appendView(
		view: ViewImpl,
		indent: Int = 1,
	) {
		startElement(view.tag, false)

		for (namespace in view.namespaceDecls) {
			linefeed()
			indent(indent)
			addSingleAttribute("xmlns:${namespace.prefix}", namespace.uri, true)
		}

		for (attr in view.attributes) {
			linefeed()
			indent(indent)

			val ns = attr.namespace?.prefix?.let { "$it:" } ?: ""
			addSingleAttribute("${ns}${attr.name}", attr.value, true)
		}

		if (view is IViewGroup) {
			closeStartElement()

			for (i in 0 until view.childCount) {
				linefeed()
				linefeed()
				indent(indent)
				appendView(view[i] as ViewImpl, indent + 1)
			}

			linefeed()
			linefeed()
			indent(indent - 1)
			endElement(view.tag)
		} else {
			selfCloseElement()
		}
	}

	private fun XMLBuilder.appendXmlProlog() {
		startPrologOrPI("xml")
		addSingleAttribute("version", "1.0", true)
		addSingleAttribute("encoding", "utf-8", true)
		endPrologOrPI()
	}
}
