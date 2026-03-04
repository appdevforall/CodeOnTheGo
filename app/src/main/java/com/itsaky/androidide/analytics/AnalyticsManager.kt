package com.itsaky.androidide.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import com.itsaky.androidide.analytics.gradle.BuildCompletedMetric
import com.itsaky.androidide.analytics.gradle.BuildStartedMetric
import com.itsaky.androidide.analytics.gradle.StrategySelectedMetric
import java.util.concurrent.TimeUnit

interface IAnalyticsManager {
	fun initialize()

	fun trackAppOpen()

	fun startSession()

	fun endSession()

	fun trackProjectOpened(projectPath: String)

	fun trackFeatureUsed(featureName: String)

	fun trackError(
		errorType: String,
		errorMessage: String,
	)

	fun setUserProperty(
		key: String,
		value: String,
	)

	fun trackScreenView(screenName: String)

	fun trackBuildRun(metric: BuildStartedMetric) = trackMetric(metric)

	fun trackGradleStrategySelected(metric: StrategySelectedMetric) = trackMetric(metric)

	fun trackBuildCompleted(metric: BuildCompletedMetric) = trackMetric(metric)

	fun trackMetric(metric: Metric)
}

class AnalyticsManager : IAnalyticsManager {
	private val analytics: FirebaseAnalytics by lazy {
		Firebase.analytics.apply {
			setAnalyticsCollectionEnabled(true)
		}
	}

	private var sessionStartTime: Long = 0

	override fun initialize() {
		trackAppOpen()
		startSession()
	}

	override fun trackAppOpen() {
		val bundle =
			Bundle().apply {
				putLong("timestamp", System.currentTimeMillis())
			}
		analytics.logEvent("app_opened", bundle)

		val dauBundle =
			Bundle().apply {
				putLong("date", System.currentTimeMillis())
			}
		analytics.logEvent("daily_active_user", dauBundle)
	}

	override fun startSession() {
		sessionStartTime = System.currentTimeMillis()
		val bundle =
			Bundle().apply {
				putLong("timestamp", sessionStartTime)
			}
		analytics.logEvent("session_started", bundle)
	}

	override fun endSession() {
		if (sessionStartTime > 0) {
			val sessionDuration = System.currentTimeMillis() - sessionStartTime
			val durationInMinutes = TimeUnit.MILLISECONDS.toMinutes(sessionDuration)

			val endBundle =
				Bundle().apply {
					putLong("duration_ms", sessionDuration)
					putLong("duration_minutes", durationInMinutes)
				}
			analytics.logEvent("session_ended", endBundle)

			val stickinessBundle =
				Bundle().apply {
					putLong("session_minutes", durationInMinutes)
					putLong("timestamp", System.currentTimeMillis())
				}
			analytics.logEvent("time_spent_in_app", stickinessBundle)

			sessionStartTime = 0
		}
	}

	override fun trackProjectOpened(projectPath: String) {
		val bundle =
			Bundle().apply {
				putLong("project_hash", projectPath.hashCode().toLong())
				putLong("timestamp", System.currentTimeMillis())
			}
		analytics.logEvent("project_opened", bundle)
	}

	override fun trackFeatureUsed(featureName: String) {
		val bundle =
			Bundle().apply {
				putString("feature_name", featureName)
				putLong("timestamp", System.currentTimeMillis())
			}
		analytics.logEvent("feature_used", bundle)
	}

	override fun trackError(
		errorType: String,
		errorMessage: String,
	) {
		val bundle =
			Bundle().apply {
				putString("error_type", errorType)
				putLong("error_hash", errorMessage.hashCode().toLong())
				putLong("timestamp", System.currentTimeMillis())
			}
		analytics.logEvent("app_error", bundle)
	}

	override fun setUserProperty(
		key: String,
		value: String,
	) {
		analytics.setUserProperty(key, value)
	}

	override fun trackScreenView(screenName: String) {
		val bundle =
			Bundle().apply {
				putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
				putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenName)
			}
		analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
	}

	override fun trackMetric(metric: Metric) {
		val bundle = metric.asBundle()
		bundle.putLong("timestamp", System.currentTimeMillis())

		analytics.logEvent(metric.eventName, bundle)
	}
}
