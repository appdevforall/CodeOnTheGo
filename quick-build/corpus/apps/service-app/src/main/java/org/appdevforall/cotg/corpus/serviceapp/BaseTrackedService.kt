package org.appdevforall.cotg.corpus.serviceapp

import android.app.Service
import android.util.Log

/**
 * Project-compiled service base class: puts a user-side supertype between the concrete
 * services and android.app.Service, so setup-time supertype recording and the
 * DeployPolicy supertype closure have a real chain to capture.
 */
abstract class BaseTrackedService : Service() {
	/** Short tag identifying the concrete service in logs. */
	abstract fun trackedTag(): String

	override fun onCreate() {
		super.onCreate()
		Log.d("QBServiceApp", "created service: " + trackedTag())
	}

	override fun onDestroy() {
		Log.d("QBServiceApp", "destroyed service: " + trackedTag())
		super.onDestroy()
	}
}
