package com.itsaky.androidide.gradle.quickbuild

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Manifest facts CoGo needs after the proxy app build; written by the generate task, merged
 * with the APK path into build/quickbuild/setup.json by the report task.
 */
data class ManifestInfo(
	val proxyAppId: String,
	val entryActivity: String?,
	val activities: List<String>,
	val components: List<ProxiedComponent> = emptyList(),
)

/**
 * Serializes every JSON payload the proxy app build emits. Uses Gradle's bundled Groovy JSON
 * support so the plugin needs no extra dependency.
 */
object QuickBuildJson {
	/**
	 * Schema version of every payload here; v2 added component proxying. Its absence tells CoGo
	 * the installed baseline predates services/providers/restart, so restart-requiring deploys
	 * must rebaseline rather than hot-swap.
	 *
	 * Must stay in step with the reader side's `ProxyAppInfo.COMPONENT_SCHEMA_VERSION` - bump
	 * both together.
	 */
	const val SCHEMA_VERSION = 2

	/**
	 * Renders the user-class-to-proxy-class map baked into the APK as
	 * assets/quickbuild/components.json, which the runtime uses to translate a user component
	 * FQN into the manifest-declared proxy it must launch.
	 *
	 * A flat string map plus a "schema" key, so the runtime's ComponentMap parser reads v1 and
	 * v2 alike. The Application has no proxy and is not in the map.
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
				"proxyAppId" to info.proxyAppId,
				"entryActivity" to info.entryActivity,
				"activities" to info.activities,
				"components" to info.components.map { componentMap(it, supertypes = null) },
			),
		)

	/**
	 * Renders build/quickbuild/setup.json, the report CoGo reads after the proxy app build.
	 *
	 * @param supertypes per-userClass supertype chains, project-compiled classes only, merged
	 *   into each `components` entry; the deploy policy's restart closure comes from these
	 * @param annotationProcessors coordinates on the variant's `ksp`/`kapt`/
	 *   `annotationProcessor` configurations; non-empty switches CoGo's classifier into
	 *   annotation-aware mode
	 * @param sourceRoots every java/kotlin source directory of the variant, generated roots
	 *   included, so the daemon compiles processor output alongside user sources
	 * @param stableIdsPath AGP's `stableIds.txt` from the real resource processing, or null if
	 *   this AGP version/variant produced none. The daemon passes it to `aapt2 link
	 *   --stable-ids` so relinking the project's own res/ - a subset of what the real build
	 *   merged - keeps the numeric ids the baseline manifest was compiled against.
	 * @param libraryResourcePaths pre-compiled `.flat` resources from the real AGP resource
	 *   processing, passed to `aapt2 link` as `-R` overlays so a relink still resolves
	 *   resources that only a dependency AAR declares. Empty if this AGP version/variant
	 *   produced none.
	 */
	fun proxyAppReportJson(
		info: ManifestInfo,
		apkPath: String,
		classpath: List<String> = emptyList(),
		proxyClassesDir: String? = null,
		manifestPath: String? = null,
		payloadJars: List<String> = emptyList(),
		composeEnabled: Boolean = false,
		supertypes: Map<String, List<String>> = emptyMap(),
		annotationProcessors: List<String> = emptyList(),
		sourceRoots: List<String> = emptyList(),
		stableIdsPath: String? = null,
		libraryResourcePaths: List<String> = emptyList(),
	): String {
		val map =
			linkedMapOf(
				"schema" to SCHEMA_VERSION,
				"proxyAppId" to info.proxyAppId,
				"entryActivity" to info.entryActivity,
				"activities" to info.activities,
				"components" to
					info.components.map {
						componentMap(it, supertypes = supertypes[it.userClass].orEmpty())
					},
				"apkPath" to apkPath,
				// For the on-device daemon: what the proxy app build compiled against, the
				// compiled proxies every later payload must bundle, and the transformed
				// manifest relinks must use (proxy-app package, proxy names).
				"classpath" to classpath,
				"proxyClassesDir" to proxyClassesDir,
				"manifestPath" to manifestPath,
				// Generated jars diverted out of the APK (R.jar and kin): hot compiles
				// reference R, which is on neither the variant compile classpath nor
				// any source the incremental engine owns.
				"payloadJars" to payloadJars,
				// The daemon adds its bundled Compose compiler plugin when true.
				"composeEnabled" to composeEnabled,
				// Together these keep a processor-using project on the live reload path
				// for edits that miss processor input, instead of rebaselining on save.
				"annotationProcessors" to annotationProcessors,
				"sourceRoots" to sourceRoots,
				"stableIdsPath" to stableIdsPath,
				"libraryResourcePaths" to libraryResourcePaths,
			)
		return pretty(map)
	}

	/** Parses [manifestInfoJson] output. Throws [IllegalArgumentException] on malformed input. */
	fun parseManifestInfo(json: String): ManifestInfo {
		val map =
			JsonSlurper().parseText(json) as? Map<*, *>
				?: throw IllegalArgumentException("manifest info is not a JSON object")
		val proxyAppId =
			// "testAppId" is the legacy key: a manifest-info.json intermediate on device may
			// predate the proxy-app vocabulary rename.
			map["proxyAppId"] as? String
				?: map["testAppId"] as? String
				?: throw IllegalArgumentException("manifest info is missing 'proxyAppId'")
		return ManifestInfo(
			proxyAppId = proxyAppId,
			entryActivity = map["entryActivity"] as? String,
			activities = (map["activities"] as? List<*>).orEmpty().filterIsInstance<String>(),
			components =
				(map["components"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map(::parseComponent),
		)
	}

	/**
	 * Renders one `components` entry, omitting every field that does not apply to the component.
	 *
	 * Intent filters, exported and permission are deliberately absent: they transfer verbatim in
	 * the manifest and no JSON consumer reads them.
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
