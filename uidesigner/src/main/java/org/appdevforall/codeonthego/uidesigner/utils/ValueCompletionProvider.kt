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

import android.text.Editable
import org.appdevforall.codeonthego.inflater.internal.ViewImpl
import org.appdevforall.codeonthego.lsp.util.setupLookupForCompletion
import org.appdevforall.codeonthego.lsp.xml.models.XMLServerSettings
import org.appdevforall.codeonthego.lsp.xml.providers.XmlCompletionProvider
import org.appdevforall.codeonthego.lsp.xml.providers.completion.AttrValueCompletionProvider
import org.appdevforall.codeonthego.tasks.runOnUiThread
import org.appdevforall.codeonthego.utils.SingleTextWatcher
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Provides completion for attribute value in the UI designer.
 *
 * @author Akash Yadav
 */
internal class ValueCompletionProvider(
	private val file: File,
	private val view: ViewImpl,
	private val attribute: org.appdevforall.codeonthego.inflater.IAttribute,
	private val onComplete: (List<String>) -> Unit,
) : SingleTextWatcher() {
	private val completionProvider =
		AttrValueCompletionProvider(XmlCompletionProvider(XMLServerSettings)).apply {
			setNamespaces(view.findNamespaces().map { it.prefix to it.uri }.toSet())
		}

	private var completionThread: CompletionThread? = null

	override fun afterTextChanged(s: Editable?) {
		if (completionThread?.isAlive == true) {
			completionThread?.cancel()
			completionThread = null
		}

		setupLookupForCompletion(file)
		val value = s?.toString() ?: ""
		completionThread =
			CompletionThread(completionProvider) { runOnUiThread { onComplete(it) } }
				.apply {
					this.attribute = this@ValueCompletionProvider.attribute
					this.prefix = value
					this.start()
				}
	}

	class CompletionThread(
		private val completionProvider: AttrValueCompletionProvider,
		private val onComplete: (List<String>) -> Unit,
	) : Thread("AttributeValueCompletionThread") {
		var prefix: String = ""
		var attribute: org.appdevforall.codeonthego.inflater.IAttribute? = null

		companion object {
			private val log = LoggerFactory.getLogger(CompletionThread::class.java)
		}

		fun cancel() {
			interrupt()
		}

		override fun run() {
			val attribute =
				this.attribute
					?: run {
						onComplete(emptyList())
						return
					}

			val ns = attribute.namespace?.prefix?.let { "$it:" } ?: ""
			log.info("Complete attribute value: '{}{}'", ns, attribute.name)

			val result =
				completionProvider.completeValue(
					namespace = attribute.namespace?.uri,
					prefix = prefix,
					attrName = attribute.name,
					attrValue = prefix,
				)

			log.debug(
				"Found {} items{}",
				result.items.size,
				if (result.isIncomplete) "(incomplete)" else "",
			)

			onComplete(result.items.map { it.ideLabel })
		}
	}
}
