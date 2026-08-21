package com.itsaky.androidide.gradle.quickbuild

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Manifest facts CoGo needs after the proxy app build; written by the generate task, merged
 * with the APK path into `build/quickbuild/<variant>/setup.json` by the report task.
 *
 * @property proxyAppId the proxy app's application id - the project's real applicationId, with no
 *   suffix, since the proxy app installs in the real app's place.
 * @property entryActivity user class of the LAUNCHER activity, or null when the manifest declares
 *   none; CoGo then has nothing to launch after installing.
 * @property activities user classes of the proxied activities, in manifest order.
 * @property components every component the transform recorded, the proxy-less Application entry
 *   included; empty when read back from a schema-1 intermediate.
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
	 * Intermediate file carrying manifest facts from the generate task to the report task.
	 *
	 * @param info the facts to serialize.
	 * @return pretty-printed JSON, whose component entries carry no `supertypes` - the classes are
	 *   not compiled yet at generate time, so [proxyAppReportJson] adds them.
	 */
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
	 * Renders `build/quickbuild/<variant>/setup.json`, the report CoGo reads after the proxy app
	 * build.
	 *
	 * @param info the manifest facts from the generate task's intermediate
	 * @param apkPath absolute path of the built proxy APK for CoGo to install
	 * @param classpath absolute jar/dir paths of the variant compile classpath, snapshotted here
	 *   so the daemon's per-session `configure` needs no re-resolution
	 * @param proxyClassesDir absolute path of the compiled proxies, which every later payload dex
	 *   must bundle; null only if the build produced none
	 * @param manifestPath absolute path of the transformed (proxy-app) manifest every resource
	 *   relink must link against - the real merged manifest names user classes the proxy app does
	 *   not declare
	 * @param payloadJars absolute paths of the generated jars diverted out of the APK (R.jar and
	 *   kin), which hot compiles reference but no source root owns
	 * @param composeEnabled true when the project uses Compose, which makes the daemon add its
	 *   bundled Compose compiler plugin to every compile
	 * @param supertypes per-userClass supertype chains, project-compiled classes only, merged
	 *   into each `components` entry; the deploy policy's restart closure comes from these
	 * @param annotationProcessors coordinates on the variant's `ksp`/`kapt`/
	 *   `annotationProcessor` configurations; non-empty switches CoGo's classifier into
	 *   annotation-aware mode
	 * @param sourceRoots every java/kotlin source directory of the variant, generated roots
	 *   included, so the daemon compiles processor output alongside user sources
	 * @param stableIdsPath AGP's `stableIds.txt`, passed to `aapt2 link --stable-ids` so relinking
	 *   the project's own res/ keeps the ids the baseline manifest was compiled against, or null
	 *   if this AGP version/variant produced none
	 * @param libraryResourcePaths pre-compiled `.flat` resources from the real AGP resource
	 *   processing, passed to `aapt2 link` as `-R` overlays so a relink still resolves resources
	 *   that only a dependency AAR declares
	 * @param minApi the API level this build dexed the seed payload at. The daemon must dex its
	 *   increments at the same level, or a project whose effective min API differs from the
	 *   daemon's own default gets a baseline and increments desugared against different targets.
	 *   Null writes an explicit JSON null, which reads back as that default.
	 * @return pretty-printed JSON, ready to write as setup.json
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
		minApi: Int? = null,
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
				// The API level this build dexed the seed payload at. Increments patch that
				// baseline, so the daemon must dex them at the same level rather than fall
				// back to its own floor.
				"minApi" to minApi,
			)
		return pretty(map)
	}

	/**
	 * Parses [manifestInfoJson] output. Throws [IllegalArgumentException] on malformed input.
	 *
	 * @param json the intermediate file's whole text.
	 * @return the parsed facts; unknown keys are ignored, so a newer writer stays readable.
	 * @throws IllegalArgumentException if the text is not a JSON object, carries no application
	 *   id, or holds a component entry missing `type` or `userClass`.
	 */
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
	 *
	 * @param component the entry to render.
	 * @param supertypes the component's project-compiled supertype chain, or null to omit the
	 *   `supertypes` key entirely - which is how the generate-time intermediate is written.
	 * @return the entry's key/value pairs, in a stable insertion order.
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
		supertypes?.let { map["supertypes"] = it }
		return map
	}

	/**
	 * Parses one `components` entry back into a [ProxiedComponent].
	 *
	 * @param map the entry as JsonSlurper produced it.
	 * @return the component; a `supertypes` key, if present, is dropped since only CoGo reads it.
	 * @throws IllegalArgumentException if `type` is missing or unknown, or `userClass` is missing.
	 */
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
		)
	}

	/**
	 * Renders a payload map as indented JSON.
	 *
	 * @param value the payload; null-valued keys are emitted as JSON null, not dropped.
	 * @return the pretty-printed text, without a trailing newline.
	 */
	private fun pretty(value: Map<String, Any?>): String = JsonOutput.prettyPrint(JsonOutput.toJson(value))
}
