package com.itsaky.androidide.analytics

import android.os.Bundle

/**
 * A metric that can be sent to the server.
 *
 * @author Akash Yadav
 */
interface Metric {

	/**
	 * The name of the event used to log this metric.
	 */
	val eventName: String

	/**
	 * Get this metric as a [Bundle].
	 */
	fun asBundle(): Bundle
}