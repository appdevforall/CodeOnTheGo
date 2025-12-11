package com.itsaky.androidide.utils

import android.os.StrictMode
import org.slf4j.LoggerFactory

@PublishedApi
internal val logger = LoggerFactory.getLogger("StrictModeExts")

inline fun copyThreadPolicy(
	source: StrictMode.ThreadPolicy,
	crossinline builder: StrictMode.ThreadPolicy.Builder.() -> Unit
): StrictMode.ThreadPolicy = StrictMode.ThreadPolicy.Builder(source).apply(builder).build()

inline fun <R> permitThreadPolicy(
	action: String,
	reason: String,
	crossinline task: () -> R,
	crossinline configurePolicy: StrictMode.ThreadPolicy.Builder.() -> Unit
): R {
	val currentPolicy = StrictMode.getThreadPolicy()
	val newPolicy = copyThreadPolicy(currentPolicy, configurePolicy)
	return try {
		logger.warn("Allowing {} because: {}", action, reason)
		StrictMode.setThreadPolicy(newPolicy)
		task()
	} finally {
		StrictMode.setThreadPolicy(currentPolicy)
	}
}

/**
 * Perform the given [task] temporarily allowing disk reads on the current thread.
 *
 * @param reason The reason for the exemption.
 * @param task The task to perform.
 * @return The result of the task.
 */
inline fun <R> allowThreadDiskReads(reason: String, crossinline task: () -> R): R =
	permitThreadPolicy("disk read", reason, task) { permitDiskReads() }