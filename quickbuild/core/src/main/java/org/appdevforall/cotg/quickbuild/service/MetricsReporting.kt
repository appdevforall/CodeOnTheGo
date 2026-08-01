package org.appdevforall.cotg.quickbuild.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@PublishedApi
internal val metricsLog: Logger = LoggerFactory.getLogger("QuickBuildMetrics")

/**
 * Metrics can never affect a build: a throwing sink degrades to a logged warning. Every
 * metrics call in this package routes through here, so the guarantee holds wherever the
 * sink is called from rather than per class that remembers to wrap it.
 */
internal inline fun report(block: () -> Unit) {
	try {
		block()
	} catch (e: Throwable) {
		metricsLog.warn("Quick Build metrics sink failed", e)
	}
}
