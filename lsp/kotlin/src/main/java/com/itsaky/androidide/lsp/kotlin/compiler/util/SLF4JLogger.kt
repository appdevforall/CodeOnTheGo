package com.itsaky.androidide.lsp.kotlin.compiler.util

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.slf4j.LoggerFactory

class SLF4JLogger(
	name: String,
) : Logger() {
	private val logger = LoggerFactory.getLogger(name)

	companion object {
		/**
		 * [message] with [details] summarized by count and length, never inlined.
		 *
		 * IntelliJ passes attachments as details, and they can be whole file texts: an "Inconsistent
		 * tree" error carries the file twice, which inlined wrote thousands of log lines per keystroke.
		 */
		fun errorMessage(
			message: String?,
			details: Array<out String?>,
		): String {
			val msg = message ?: ""
			val attachments = details.filterNotNull()
			if (attachments.isEmpty()) return msg
			val lengths = attachments.joinToString { it.length.toString() }
			return "$msg (${attachments.size} attachments omitted, lengths: $lengths)"
		}
	}

	override fun isDebugEnabled(): Boolean = logger.isDebugEnabled

	override fun debug(
		message: String?,
		t: Throwable?,
	) {
		if (t != null) {
			logger.debug(message ?: "", t)
		} else {
			logger.debug(message ?: "")
		}
	}

	override fun info(
		message: String?,
		t: Throwable?,
	) {
		if (t != null) {
			logger.info(message ?: "", t)
		} else {
			logger.info(message ?: "")
		}
	}

	override fun warn(
		message: String?,
		t: Throwable?,
	) {
		if (t != null) {
			logger.warn(message ?: "", t)
		} else {
			logger.warn(message ?: "")
		}
	}

	override fun error(
		message: String?,
		t: Throwable?,
		vararg details: String?,
	) {
		val fullMessage = errorMessage(message, details)
		if (t != null) {
			logger.error(fullMessage, t)
		} else {
			logger.error(fullMessage)
		}
	}
}
