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
	val components: List<ProxiedComponent> = emptyList(),
)

/**
 * JSON payloads the setup build emits. Uses Gradle's bundled Groovy JSON support so the
 * plugin needs no extra dependency; kept as pure functions for unit testing.
 */
object QuickBuildJson {
	/**
	 * Marks schema v2 (component proxying): its absence tells CoGo the installed baseline
	 * predates services/providers/restart, so restart-requiring deploys must rebaseline
	 * instead of hot-swapping stale.
	 */
	const val SCHEMA_VERSION = 2

	/**
	 * The user-class-to-proxy-class map baked into the APK as
	 * assets/quickbuild/components.json. The runtime translates a user component FQN
	 * (e.g. metadataJson.entryActivity) into the manifest-declared proxy component it
	 * must launch - manifest names are stable proxies, user classes stay swappable.
	 *
	 * v2: stays a FLAT string map (the runtime's ComponentMap parser accepts it
	 * unchanged) with service/receiver/provider entries added and a "schema": "2"
	 * marker. The Application has no proxy and is not in the map.
	 */
	fun componentsJson(components: List<ProxiedComponent>): String {
		val map = linkedMapOf<String, Any?>("schema" to SCHEMA_VERSION.toString())
		components.forEach { component ->
			component.proxyClass?.let { map[component.userClass] = it }
		}
		return pretty(map)
	}

	/** Intermediate file carrying manifest facts from the generate task to the report task. */
	fun manifestInfoJson(info: ManifestInfo): String =
		pretty(
			linkedMapOf(
				"schema" to SCHEMA_VERSION,
				"testAppId" to info.testAppId,
				"entryActivity" to info.entryActivity,
				"activities" to info.activities,
				"components" to info.components.map { componentMap(it, supertypes = null) },
			),
		)

	/**
	 * The setup report CoGo reads after the setup build (build/quickbuild/setup.json).
	 *
	 * @param supertypes per-userClass user-side superclass chains (project-compiled classes
	 *   only), merged into each `components` entry - the deploy policy's restart closure
	 *   comes from these.
	 * @param sameAppId true for a same-app-id (Path B) setup build; testAppId then equals
	 *   the real applicationId.
	 * @param versionCode the versionCode CoGo pinned for the same-app-id episode, or null.
	 */
	fun setupJson(
		info: ManifestInfo,
		apkPath: String,
		classpath: List<String> = emptyList(),
		proxyClassesDir: String? = null,
		manifestPath: String? = null,
		payloadJars: List<String> = emptyList(),
		composeEnabled: Boolean = false,
		supertypes: Map<String, List<String>> = emptyMap(),
		sameAppId: Boolean = false,
		versionCode: Int? = null,
	): String {
		val map =
			linkedMapOf(
				"schema" to SCHEMA_VERSION,
				"testAppId" to info.testAppId,
				"entryActivity" to info.entryActivity,
				"activities" to info.activities,
				"components" to
					info.components.map {
						componentMap(it, supertypes = supertypes[it.userClass].orEmpty())
					},
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
			)
		// Same-app-id mode fields are ADDITIVE and absent in suffix mode - no schema bump,
		// old parsers ignore them. String values per the design contract
		// (quick-build/docs/same-app-id-design.md, section 6).
		if (sameAppId) {
			map["sameAppId"] = "true"
		}
		versionCode?.let { map["versionCode"] = it.toString() }
		return pretty(map)
	}

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
			components =
				(map["components"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map(::parseComponent),
		)
	}

	/**
	 * One `components` entry: type + userClass always; proxyClass when the component has a
	 * proxy; launcher for activities; foregroundServiceType/authorities when present;
	 * supertypes only where known (setup.json). Intent filters, exported, permission are
	 * NOT here by design - they transfer verbatim in the manifest and no JSON consumer
	 * reads them.
	 */
	private fun componentMap(
		component: ProxiedComponent,
		supertypes: List<String>?,
	): Map<String, Any?> {
		val map = linkedMapOf<String, Any?>()
		map["type"] = component.type.jsonName
		map["userClass"] = component.userClass
		component.proxyClass?.let { map["proxyClass"] = it }
		if (component.type == ComponentType.ACTIVITY) {
			map["launcher"] = component.isLauncher
		}
		component.foregroundServiceType?.let { map["foregroundServiceType"] = it }
		if (component.authorities.isNotEmpty()) {
			map["authorities"] = component.authorities
		}
		supertypes?.let { map["supertypes"] = it }
		return map
	}

	private fun parseComponent(map: Map<*, *>): ProxiedComponent {
		val typeName =
			map["type"] as? String
				?: throw IllegalArgumentException("component entry is missing 'type'")
		val type =
			ComponentType.entries.firstOrNull { it.jsonName == typeName }
				?: throw IllegalArgumentException("unknown component type '$typeName'")
		val userClass =
			map["userClass"] as? String
				?: throw IllegalArgumentException("component entry is missing 'userClass'")
		return ProxiedComponent(
			type = type,
			userClass = userClass,
			proxyClass = map["proxyClass"] as? String,
			isLauncher = map["launcher"] == true,
			foregroundServiceType = map["foregroundServiceType"] as? String,
			authorities = (map["authorities"] as? List<*>).orEmpty().filterIsInstance<String>(),
		)
	}

	private fun pretty(value: Map<String, Any?>): String = JsonOutput.prettyPrint(JsonOutput.toJson(value))
}
