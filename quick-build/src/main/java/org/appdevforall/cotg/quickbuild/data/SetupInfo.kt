package org.appdevforall.cotg.quickbuild.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The setup build's output manifest (`build/quickbuild/setup.json`, written by the
 * Gradle-plugin side of the feature). Parsing is tolerant about key aliases because
 * the exact field names are a cross-agent contract pinned only by convention tonight -
 * the primary names are listed first per field.
 */
data class SetupInfo(
	/** The generated test app's applicationId (`<user appId>.quickbuild`). */
	val testAppPackage: String,
	/** Fully-qualified user entry activity carried in every deploy metadata. */
	val entryActivity: String,
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
) {
	companion object {
		private val log = LoggerFactory.getLogger(SetupInfo::class.java)

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
			val entry =
				obj.firstString("entryActivity", "mainActivity") ?: return missing("entryActivity")
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
