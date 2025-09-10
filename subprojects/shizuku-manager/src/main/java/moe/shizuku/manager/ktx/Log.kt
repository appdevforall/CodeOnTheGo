@file:Suppress("NOTHING_TO_INLINE")

package moe.shizuku.manager.ktx

import org.slf4j.LoggerFactory

inline val <reified T> T.TAG: String
	get() =
		T::class.java.simpleName.let {
			if (it.isBlank()) throw IllegalStateException("tag is empty")
			if (it.length > 23) it.substring(0, 23) else it
		}

inline fun <reified T> T.logv(
	message: String,
	throwable: Throwable? = null,
) = logv(TAG, message, throwable)

inline fun <reified T> T.logi(
	message: String,
	throwable: Throwable? = null,
) = logi(TAG, message, throwable)

inline fun <reified T> T.logw(
	message: String,
	throwable: Throwable? = null,
) = logw(TAG, message, throwable)

inline fun <reified T> T.logd(
	message: String,
	throwable: Throwable? = null,
) = logd(TAG, message, throwable)

inline fun <reified T> T.loge(
	message: String,
	throwable: Throwable? = null,
) = loge(TAG, message, throwable)

inline fun <reified T> T.logv(
	tag: String,
	message: String,
	throwable: Throwable? = null,
) = LoggerFactory.getLogger(tag).trace(message, throwable)

inline fun <reified T> T.logi(
	tag: String,
	message: String,
	throwable: Throwable? = null,
) = LoggerFactory.getLogger(tag).info(message, throwable)

inline fun <reified T> T.logw(
	tag: String,
	message: String,
	throwable: Throwable? = null,
) = LoggerFactory.getLogger(tag).warn(message, throwable)

inline fun <reified T> T.logd(
	tag: String,
	message: String,
	throwable: Throwable? = null,
) = LoggerFactory.getLogger(tag).debug(message, throwable)

inline fun <reified T> T.loge(
	tag: String,
	message: String,
	throwable: Throwable? = null,
) = LoggerFactory.getLogger(tag).error(message, throwable)
