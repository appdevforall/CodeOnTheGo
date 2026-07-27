package org.appdevforall.cotg.quickbuild.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.domain.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The setup build's output manifest (`build/quickbuild/setup.json`, written by the
 * Gradle-plugin side of the feature). Parsing is tolerant about key aliases because
 * the exact field names are a cross-agent contract pinned only by convention tonight -
 * the primary names are listed first per field.
 */
data class SetupInfo(
	/** The generated test app's applicationId - the project's real applicationId. */
	val testAppPackage: String,
	/**
	 * Fully-qualified user entry activity carried in every deploy metadata. Null when
	 * the setup build found no launchable Activity (ADFA-4128 Bug 10 - e.g. the
	 * No-Activity template): the build itself succeeded, there's just nothing for
	 * Quick Build to install/launch. [org.appdevforall.cotg.quickbuild.service.QuickBuildProvisioner]
	 * callers must refuse with a friendly message before this null ever reaches a
	 * [ProvisionOutcome.Success][org.appdevforall.cotg.quickbuild.service.ProvisionOutcome.Success].
	 */
	val entryActivity: String?,
	/** The built test-app APK to install. */
	val apk: File,
	/** Compile classpath for the daemon; optional in the JSON. */
	val classpath: List<File>,
	/**
	 * Compiled proxy classes from the setup build; the executor bundles them into
	 * every payload dex (the proxies must ride with the user classes they extend).
	 * Optional in the JSON.
	 */
	val proxyClassesDir: File?,
	/**
	 * The setup build's TRANSFORMED manifest (test-app package + proxy component
	 * names); resource relinks must link against it, not the user's raw manifest.
	 * Optional in the JSON.
	 */
	val transformedManifest: File?,
	/**
	 * True when the setup build detected Jetpack Compose in the user project; the
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
	 * The manifest components the setup build recorded (schema v2 `components`);
	 * empty for pre-v2 baselines. Feeds the restart closure and the relaunch target.
	 */
	val components: List<ComponentInfo> = emptyList(),
	/**
	 * KSP/kapt/annotationProcessor coordinates the setup build saw. Empty (or absent, on
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
	 * AGP's `stableIds.txt` from the setup build's real resource processing, if reported
	 * (`setup.json` `stableIdsPath`). Feeds [org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout.stableIdsFile]
	 * so relinks can pin resource ids against the baseline (ADFA-4128 Bug 6). Null when
	 * absent - an older setup.json, or a setup build whose AGP version/variant never
	 * produced the file.
	 */
	val stableIdsFile: File? = null,
	/**
	 * Pre-compiled `.flat` resource units from the setup build's real AGP resource
	 * processing (`setup.json` `libraryResourcePaths`) - the project's own merged_res
	 * closure plus every resource-providing AAR's compiled file resources. Feeds
	 * [org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout.libraryResourceFlats]
	 * so relinks can resolve a resource a dependency AAR provides (ADFA-4128 Bug 8).
	 * Empty when absent - an older setup.json, or a setup build whose AGP version/variant
	 * never produced them.
	 */
	val libraryResourceFlats: List<File> = emptyList(),
) {
	/**
	 * True when this baseline carries [components] and its baked runtime understands
	 * restart deploys ([schema] >= [COMPONENT_SCHEMA_VERSION]); the deploy policy's
	 * skew guard keys on this.
	 */
	val supportsComponentInfo: Boolean
		get() = schema >= COMPONENT_SCHEMA_VERSION

	companion object {
		private val log = LoggerFactory.getLogger(SetupInfo::class.java)

		/**
		 * The setup.json schema version that introduced `components` + runtime restart
		 * support. Must stay in step with the writer side's
		 * `QuickBuildJson.SCHEMA_VERSION` (gradle-plugin quickbuild/QuickBuildJson.kt) -
		 * bump the two together when the schema changes.
		 */
		const val COMPONENT_SCHEMA_VERSION = 2

		/**
		 * @param baseDir directory relative paths in the JSON resolve against
		 *   (the project root).
		 * @return the parsed info, or null when the JSON is malformed or misses a
		 *   required field - provisioning then fails visibly instead of crashing.
		 */
		fun parse(
			json: String,
			baseDir: File,
		): SetupInfo? {
			val obj =
				runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
					?: run {
						log.error("setup.json is not a JSON object")
						return null
					}

			val pkg =
				obj.firstString("testAppId", "testAppPackage", "applicationId", "packageName")
					?: return missing("testAppId")
			// Absent/null (a JSON null, not just a missing key - the plugin writes
			// `"entryActivity": null` for a project with no launchable Activity) is a
			// legitimate outcome of a SUCCESSFUL setup build, not a parse failure - see
			// the classification note on [SetupInfo.entryActivity].
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

			return SetupInfo(
				testAppPackage = pkg,
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
			)
		}

		/** A JSON array of strings; empty when the key is absent or not an array. */
		private fun JsonObject.stringArray(key: String): List<String> =
			getAsJsonArray(key)
				?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
				?.filter { it.isNotBlank() }
				?: emptyList()

		/** One `components` entry; null (skipped, logged) when malformed or of an unknown type. */
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

		private fun resolve(
			path: String,
			baseDir: File,
		): File = File(path).let { if (it.isAbsolute) it else File(baseDir, path) }

		private fun JsonObject.firstString(vararg keys: String): String? =
			keys.firstNotNullOfOrNull { key ->
				get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
			}

		private fun missing(field: String): SetupInfo? {
			log.error("setup.json is missing required field '{}'", field)
			return null
		}
	}
}
