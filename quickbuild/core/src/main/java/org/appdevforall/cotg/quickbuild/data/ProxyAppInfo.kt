package org.appdevforall.cotg.quickbuild.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.slf4j.LoggerFactory
import java.io.File

/**
 * What the proxy app build published about the project, read from its output manifest
 * `build/quickbuild/setup.json`.
 *
 * [parse] accepts several key aliases per field (primary name first) because the names are a
 * convention shared with the Gradle-plugin writer rather than an enforced schema.
 */
data class ProxyAppInfo(
	/** The generated proxy app's applicationId - the project's real applicationId. */
	val proxyAppPackage: String,
	/**
	 * Fully-qualified user entry activity, carried in every deploy metadata. Null when the
	 * proxy app build found no launchable Activity (e.g. the No-Activity template) - a
	 * successful build with nothing to install and launch, which
	 * [org.appdevforall.cotg.quickbuild.service.provision.QuickBuildProvisioner] callers must refuse
	 * with a friendly message rather than let through as a success.
	 */
	val entryActivity: String?,
	/** The built proxy-app APK to install. */
	val apk: File,
	/** Compile classpath for the daemon; optional in the JSON. */
	val classpath: List<File>,
	/**
	 * Compiled proxy classes from the proxy app build; the executor bundles them into
	 * every payload dex (the proxies must ride with the user classes they extend).
	 * Optional in the JSON.
	 */
	val proxyClassesDir: File?,
	/**
	 * The proxy app build's transformed manifest (proxy-app package plus proxy component
	 * names); resource relinks must link against it, not the user's raw manifest. Optional
	 * in the JSON.
	 */
	val transformedManifest: File?,
	/**
	 * True when the proxy app build detected Jetpack Compose in the user project; the
	 * daemon then compiles with the bundled Compose compiler plugin. Optional in the
	 * JSON, defaults to false.
	 */
	val composeEnabled: Boolean = false,
	/**
	 * setup.json schema version; 0 when the field is absent (a pre-v2 baseline).
	 * Schema >= 2 means the baseline carries [components] and its baked runtime
	 * understands restart deploys - the deploy policy's skew guard keys on this.
	 */
	val schema: Int = 0,
	/**
	 * The manifest components the proxy app build recorded (schema v2 `components`);
	 * empty for pre-v2 baselines. Feeds the restart closure and the relaunch target.
	 */
	val components: List<ComponentInfo> = emptyList(),
	/**
	 * KSP/kapt/annotationProcessor coordinates the proxy app build saw. Empty (or absent, on
	 * an older setup.json) means no processors, and the classifier stays in its original
	 * content-free mode; non-empty switches on annotation-aware classification.
	 */
	val annotationProcessors: List<String> = emptyList(),
	/**
	 * Every java/kotlin source root of the built variant, GENERATED roots included. The
	 * layout adds these to the daemon's source set so processor output compiles alongside
	 * user code. Absent on an older setup.json, where only the convention roots apply.
	 */
	val sourceRoots: List<File> = emptyList(),
	/**
	 * AGP's `stableIds.txt` from the proxy app build (`setup.json` `stableIdsPath`), which
	 * lets relinks pin resource ids against the baseline. Null on an older setup.json or a
	 * build whose AGP version/variant never produced the file.
	 */
	val stableIdsFile: File? = null,
	/**
	 * Pre-compiled `.flat` resource units from the proxy app build (`setup.json`
	 * `libraryResourcePaths`) - the merged_res closure plus every resource-providing AAR -
	 * which let relinks resolve resources a dependency AAR provides. Empty on an older
	 * setup.json or a build whose AGP version/variant never produced them.
	 */
	val libraryResourceFlats: List<File> = emptyList(),
	/**
	 * The API level the proxy app build dexed the seed payload at (`setup.json` `minApi`) -
	 * `max(the project's minSdk, the Quick Build floor)`. Every increment the daemon dexes
	 * patches that baseline, so it must use the same level. Falls back to
	 * [ConfigureRequest.DEFAULT_MIN_API] on an older setup.json that carries no such key, which
	 * is what the daemon assumed unconditionally before the field existed.
	 */
	val minApi: Int = ConfigureRequest.DEFAULT_MIN_API,
) {
	/** True when [schema] is at least [COMPONENT_SCHEMA_VERSION]. */
	val supportsComponentInfo: Boolean
		get() = schema >= COMPONENT_SCHEMA_VERSION

	companion object {
		private val log = LoggerFactory.getLogger("QB-ProxyAppInfo")

		/**
		 * The setup.json schema version that introduced `components` and runtime restart
		 * support. Bump together with the writer side's `QuickBuildJson.SCHEMA_VERSION`
		 * (gradle-plugin quickbuild/QuickBuildJson.kt).
		 */
		const val COMPONENT_SCHEMA_VERSION = 2

		/**
		 * Parses a setup.json document.
		 *
		 * @param json the raw file contents; anything that is not a JSON object is a parse
		 *   failure rather than a throw.
		 * @param baseDir directory the JSON's relative paths resolve against (the project root).
		 * @return the parsed info, or null when the JSON is malformed or misses a required
		 *   field - provisioning then fails visibly instead of crashing.
		 */
		fun parse(
			json: String,
			baseDir: File,
		): ProxyAppInfo? {
			val obj =
				runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
					?: run {
						log.error("setup.json is not a JSON object")
						return null
					}

			val pkg =
				// "testAppId"/"testAppPackage" are legacy aliases: a setup.json already on
				// device may predate the proxy-app vocabulary rename.
				obj.firstString("proxyAppId", "testAppId", "testAppPackage", "applicationId", "packageName")
					?: return missing("proxyAppId")
			// Absent or an explicit JSON null (the plugin writes `"entryActivity": null` for
			// a project with no launchable Activity) is a legitimate successful build, not a
			// parse failure - see [ProxyAppInfo.entryActivity].
			val entry = obj.firstString("entryActivity", "mainActivity")
			val apkPath = obj.firstString("apk", "apkPath", "apkFile") ?: return missing("apk")

			val classpath =
				obj
					.getAsJsonArray("classpath")
					?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
					?.map { resolve(it, baseDir) }
					?: emptyList()
			// Generated project-scope jars (R.jar and kin) ride the compile classpath:
			// hot compiles reference R, which the variant compile classpath lacks.
			val payloadJars =
				obj
					.getAsJsonArray("payloadJars")
					?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
					?.map { resolve(it, baseDir) }
					?: emptyList()

			return ProxyAppInfo(
				proxyAppPackage = pkg,
				entryActivity = entry,
				apk = resolve(apkPath, baseDir),
				classpath = classpath + payloadJars,
				proxyClassesDir = obj.firstString("proxyClassesDir")?.let { resolve(it, baseDir) },
				transformedManifest =
					obj
						.firstString("manifestPath", "transformedManifest")
						?.let { resolve(it, baseDir) },
				composeEnabled =
					obj
						.get("composeEnabled")
						?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
						?.asBoolean == true,
				schema =
					obj
						.get("schema")
						?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
						?.asInt ?: 0,
				components =
					obj
						.getAsJsonArray("components")
						?.mapNotNull { element -> (element as? JsonObject)?.let(::parseComponent) }
						?: emptyList(),
				annotationProcessors = obj.stringArray("annotationProcessors"),
				sourceRoots = obj.stringArray("sourceRoots").map { resolve(it, baseDir) },
				stableIdsFile = obj.firstString("stableIdsPath")?.let { resolve(it, baseDir) },
				libraryResourceFlats = obj.stringArray("libraryResourcePaths").map { resolve(it, baseDir) },
				// Absent (older setup.json) or an explicit null both fall back to the
				// protocol default - the level the daemon used before this was published.
				minApi =
					obj
						.get("minApi")
						?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
						?.asInt ?: ConfigureRequest.DEFAULT_MIN_API,
			)
		}

		/**
		 * A JSON array of strings; empty when the key is absent or not an array.
		 *
		 * @param key the array-valued key to read.
		 * @return its string elements in document order, with non-primitive and blank entries
		 *   dropped rather than treated as an error.
		 */
		private fun JsonObject.stringArray(key: String): List<String> =
			getAsJsonArray(key)
				?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
				?.filter { it.isNotBlank() }
				?: emptyList()

		/**
		 * One `components` entry; null (skipped, logged) when malformed or of an unknown type.
		 *
		 * @param obj the array element to read, expected to carry at least `type` and
		 *   `userClass`.
		 * @return the parsed component, or null to skip it - a missing required field is
		 *   silent, an unrecognized `type` is logged, and neither fails the whole parse.
		 */
		private fun parseComponent(obj: JsonObject): ComponentInfo? {
			val typeName = obj.firstString("type") ?: return null
			val kind =
				when (typeName) {
					"activity" -> {
						ComponentKind.ACTIVITY
					}

					"service" -> {
						ComponentKind.SERVICE
					}

					"receiver" -> {
						ComponentKind.RECEIVER
					}

					"provider" -> {
						ComponentKind.PROVIDER
					}

					"application" -> {
						ComponentKind.APPLICATION
					}

					else -> {
						// A future schema's component type this build doesn't know. The
						// schema version, not this parser, is the compatibility gate.
						log.warn("setup.json component of unknown type '{}' ignored", typeName)
						return null
					}
				}
			val userClass = obj.firstString("userClass") ?: return null
			return ComponentInfo(
				kind = kind,
				className = userClass,
				proxyClass = obj.firstString("proxyClass"),
				launcher =
					obj
						.get("launcher")
						?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
						?.asBoolean == true,
				supertypes =
					obj
						.getAsJsonArray("supertypes")
						?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
						?: emptyList(),
			)
		}

		/**
		 * Interprets one path from the JSON.
		 *
		 * @param path an absolute path, or one relative to [baseDir].
		 * @param baseDir the project root relative paths hang off.
		 * @return the resolved file, never checked for existence - a missing input has to surface
		 *   where it is used, with that step's context.
		 */
		private fun resolve(
			path: String,
			baseDir: File,
		): File = File(path).let { if (it.isAbsolute) it else File(baseDir, path) }

		/**
		 * Reads the first key that carries a usable string, which is how the parser accepts
		 * legacy aliases for a renamed field.
		 *
		 * @param keys candidate key names, most preferred first.
		 * @return the first non-blank primitive value found, or null when no key yields one.
		 */
		private fun JsonObject.firstString(vararg keys: String): String? =
			keys.firstNotNullOfOrNull { key ->
				get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
			}

		/**
		 * Logs a required-field failure at the one call shape [parse] uses to bail out.
		 *
		 * @param field the primary key name to name in the log, not the alias that was tried.
		 * @return always null, so the caller can `return missing(...)` in one line.
		 */
		private fun missing(field: String): ProxyAppInfo? {
			log.error("setup.json is missing required field '{}'", field)
			return null
		}
	}
}
