package org.appdevforall.cotg.quickbuild.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@PublishedApi
internal val metricsLog: Logger = LoggerFactory.getLogger("QuickBuildMetrics")

/**
 * Runs a metrics call and swallows any failure into a logged warning, so metrics can
 * never break a build.
 *
 * Every metrics call in this package goes through here rather than relying on each class
 * to remember its own try/catch.
 */
internal inline fun report(block: () -> Unit) {
	try {
		block()
	} catch (e: Throwable) {
		metricsLog.warn("Quick Build metrics sink failed", e)
	}
}
