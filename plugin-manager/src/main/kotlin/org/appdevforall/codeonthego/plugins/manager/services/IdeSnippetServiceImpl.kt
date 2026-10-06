package org.appdevforall.codeonthego.plugins.manager.services

import org.appdevforall.codeonthego.plugins.services.IdeSnippetService
import org.slf4j.LoggerFactory

class IdeSnippetServiceImpl : IdeSnippetService {

	private val log = LoggerFactory.getLogger(IdeSnippetServiceImpl::class.java)

	private var refreshCallback: ((String) -> Unit)? = null

	fun setRefreshCallback(callback: (String) -> Unit) {
		this.refreshCallback = callback
	}

	override fun refreshSnippets(pluginId: String) {
		refreshCallback?.invoke(pluginId)
			?: log.warn("No refresh callback set for snippet service")
	}
}
