package com.itsaky.androidide.lsp.kotlin.compiler.util

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.slf4j.LoggerFactory

class SLF4JLogger(name: String) : Logger() {
	private val logger = LoggerFactory.getLogger(name)

	override fun isDebugEnabled(): Boolean {
		return logger.isDebugEnabled
	}

	override fun debug(message: String?, t: Throwable?) {
		if (t != null) {
			logger.debug(message ?: "", t)
		} else {
			logger.debug(message ?: "")
		}
	}

	override fun info(message: String?, t: Throwable?) {
		if (t != null) {
			logger.info(message ?: "", t)
		} else {
			logger.info(message ?: "")
		}
	}

	override fun warn(message: String?, t: Throwable?) {
		if (t != null) {
			logger.warn(message ?: "", t)
		} else {
			logger.warn(message ?: "")
		}
	}

	override fun error(message: String?, t: Throwable?, vararg details: String?) {
		val msg = message ?: ""
		val detailStr = if (details.isNotEmpty()) {
			details.filterNotNull().joinToString(System.lineSeparator())
		} else {
			""
		}

		val fullMessage = if (detailStr.isNotEmpty()) {
			if (msg.isNotEmpty()) "$msg: $detailStr" else detailStr
		} else {
			msg
		}

		if (t != null) {
			logger.error(fullMessage, t)
		} else {
			logger.error(fullMessage)
		}
	}
}
