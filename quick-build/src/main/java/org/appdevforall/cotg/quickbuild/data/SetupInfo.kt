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
	 * True when the setup build ran in same-app-id mode (Path B): [testAppPackage] then
	 * IS the real applicationId. Additive field, absent in suffix-mode setup.json; the
	 * plugin writes it as the STRING "true", so parsing accepts both forms.
	 */
	val sameAppId: Boolean = false,
	/**
	 * The pinned versionCode the setup build applied (same-app-id episodes only);
	 * written as a numeric string. Null when absent.
	 */
	val versionCode: Int? = null,
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
				sameAppId = obj.flexibleBoolean("sameAppId"),
				versionCode = obj.flexibleInt("versionCode"),
			)
		}

		/**
		 * A boolean the plugin may write as a JSON boolean or the string "true" (the
		 * setup.json convention is string values); anything else reads as false.
		 */
		private fun JsonObject.flexibleBoolean(key: String): Boolean {
			val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
			return if (value.isBoolean) value.asBoolean else value.asString.equals("true", ignoreCase = true)
		}

		/** An int written as a JSON number or a numeric string; null when absent/malformed. */
		private fun JsonObject.flexibleInt(key: String): Int? {
			val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
			return if (value.isNumber) value.asInt else value.asString.toIntOrNull()
		}

		/** One `components` entry; null (skipped, logged) when malformed or of an unknown type. */
		private fun parseComponent(obj: JsonObject): ComponentInfo? {
			val typeName = obj.firstString("type") ?: return null
			val kind =
				when (typeName) {
					"activity" -> ComponentKind.ACTIVITY
					"service" -> ComponentKind.SERVICE
					"receiver" -> ComponentKind.RECEIVER
					"provider" -> ComponentKind.PROVIDER
					"application" -> ComponentKind.APPLICATION
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
