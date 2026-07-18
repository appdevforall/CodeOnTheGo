package com.itsaky.androidide.gradle.quickbuild

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Manifest facts CoGo needs after the setup build; written by the generate task, merged
 * with the APK path into build/quickbuild/setup.json by the report task.
 */
data class ManifestInfo(
	val testAppId: String,
	val entryActivity: String?,
	val activities: List<String>,
)

/**
 * JSON payloads the setup build emits. Uses Gradle's bundled Groovy JSON support so the
 * plugin needs no extra dependency; kept as pure functions for unit testing.
 */
object QuickBuildJson {
	/**
	 * The user-class-to-proxy-class map baked into the APK as
	 * assets/quickbuild/components.json. The runtime translates a user activity FQN
	 * (e.g. metadataJson.entryActivity) into the manifest-declared proxy component it
	 * must launch — manifest names are stable proxies, user classes stay swappable.
	 */
	fun componentsJson(activities: List<ProxiedActivity>): String = pretty(activities.associate { it.userClass to it.proxyClass })

	/** Intermediate file carrying manifest facts from the generate task to the report task. */
	fun manifestInfoJson(info: ManifestInfo): String =
		pretty(
			linkedMapOf(
				"testAppId" to info.testAppId,
				"entryActivity" to info.entryActivity,
				"activities" to info.activities,
			),
		)

	/** The setup report CoGo reads after the setup build (build/quickbuild/setup.json). */
	fun setupJson(
		info: ManifestInfo,
		apkPath: String,
		classpath: List<String> = emptyList(),
		proxyClassesDir: String? = null,
		manifestPath: String? = null,
		payloadJars: List<String> = emptyList(),
		composeEnabled: Boolean = false,
	): String =
		pretty(
			linkedMapOf(
				"testAppId" to info.testAppId,
				"entryActivity" to info.entryActivity,
				"activities" to info.activities,
				"apkPath" to apkPath,
				// For the on-device daemon: what the setup build compiled against, the
				// compiled proxies every later payload must bundle, and the TRANSFORMED
				// manifest resource relinks must use (test-app package, proxy names).
				"classpath" to classpath,
				"proxyClassesDir" to proxyClassesDir,
				"manifestPath" to manifestPath,
				// Project-scope GENERATED jars diverted out of the APK (R.jar and kin):
				// hot compiles reference R, which is neither on the variant compile
				// classpath nor a source the incremental engine owns.
				"payloadJars" to payloadJars,
				// The daemon adds its bundled Compose compiler plugin when true.
				"composeEnabled" to composeEnabled,
			),
		)

	/** Parses [manifestInfoJson] output. Throws [IllegalArgumentException] on malformed input. */
	fun parseManifestInfo(json: String): ManifestInfo {
		val map =
			JsonSlurper().parseText(json) as? Map<*, *>
				?: throw IllegalArgumentException("manifest info is not a JSON object")
		val testAppId =
			map["testAppId"] as? String
				?: throw IllegalArgumentException("manifest info is missing 'testAppId'")
		return ManifestInfo(
			testAppId = testAppId,
			entryActivity = map["entryActivity"] as? String,
			activities = (map["activities"] as? List<*>).orEmpty().filterIsInstance<String>(),
		)
	}

	private fun pretty(value: Map<String, Any?>): String = JsonOutput.prettyPrint(JsonOutput.toJson(value))
}
