package com.itsaky.androidide.templates.impl.zip

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.Parameter
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.RecipeExecutor
import com.itsaky.androidide.templates.TemplateRecipe
import com.itsaky.androidide.templates.impl.R
import com.itsaky.androidide.templates.impl.base.ProjectTemplateRecipeResultImpl
import com.itsaky.androidide.utils.Environment
import dalvik.system.DexClassLoader
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.PebbleException
import io.pebbletemplates.pebble.extension.Extension
import io.pebbletemplates.pebble.lexer.Syntax
import io.pebbletemplates.pebble.loader.StringLoader
import org.adfa.constants.ANDROID_GRADLE_PLUGIN_VERSION
import org.adfa.constants.KOTLIN_VERSION
import org.adfa.constants.Sdk
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringWriter
import java.util.ServiceLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Fatal template error: execution terminates, the partially created project
 * directory is removed and project creation is reported as failed.
 */
class TemplateExecutionException(
	message: String,
	cause: Throwable?,
) : RuntimeException(message, cause)

class ZipRecipeExecutor(
	private val zipProvider: () -> ZipFile,
	private val metaJson: TemplateJson,
	private val params: MutableMap<String, Parameter<*>>,
	private val basePath: String,
	private val data: ProjectTemplateData,
	private val defModule: ModuleTemplateData,
) : TemplateRecipe<ProjectTemplateRecipeResult> {
	var hasErrorsWarnings: Boolean = false

	companion object {
		private val log = LoggerFactory.getLogger(ZipRecipeExecutor::class.java)
		private val CLASS_NAME_PATTERN = Regex("[^a-zA-Z0-9]")
	}

	override fun execute(executor: RecipeExecutor): ProjectTemplateRecipeResult {
		val ctx = requireNotNull(executor.context) { "context null" }

		info(ctx, R.string.template_exec_info_basepath, basePath)

		val projectDir = data.projectDir
		if (projectDir.exists()) {
			return ProjectTemplateRecipeResultImpl(data, hasErrorsWarnings)
		}

		try {
			renderProject(ctx, projectDir)
		} catch (e: Exception) {
			// A partial project must not survive: the exists() check above would
			// treat it as an already-created project on the next attempt.
			if (!projectDir.deleteRecursively()) {
				warn(ctx, R.string.template_exec_warn_cleanup_failed, projectDir.absolutePath)
			}
			throw TemplateExecutionException(
				ctx.getString(R.string.template_exec_error_terminated, e.message ?: e.toString()),
				e,
			)
		}

		try {
			keystore(executor)
		} catch (e: Exception) {
			// The release keystore is auxiliary (only needed for release signing);
			// failing to copy it must not discard the rendered project.
			error("Failed to copy release keystore into ${projectDir.absolutePath}", e)
		}

		return ProjectTemplateRecipeResultImpl(data, hasErrorsWarnings)
	}

	private fun renderProject(
		ctx: Context,
		projectDir: File,
	) {
		val projectRoot = projectDir.canonicalFile

		val flags: Map<String, Boolean> =
			params
				.mapNotNull { (identifier, param) ->
					(param.value as? Boolean)?.let { identifier to it }
				}.toMap()

		zipProvider().use { zip ->

			val customSyntax =
				Syntax
					.Builder()
					.setPrintOpenDelimiter(DELIM_PRINT_OPEN)
					.setPrintCloseDelimiter(DELIM_PRINT_CLOSE)
					.setExecuteOpenDelimiter(DELIM_EXECUTE_OPEN)
					.setExecuteCloseDelimiter(DELIM_EXECUTE_CLOSE)
					.setCommentOpenDelimiter(DELIM_COMMENT_OPEN)
					.setCommentCloseDelimiter(DELIM_COMMENT_CLOSE)
					.build()

			val builder = PebbleEngine.Builder()

			val extensionsEntry = zip.getEntry(META_EXTENSION_JAR)
			if (extensionsEntry != null) {
				val extensions = loadExtensionFromArchive(zip, extensionsEntry, ctx)
				for (ext in extensions) {
					builder.extension(ext)
				}
			}

			val pebbleEngine =
				builder
					.loader(StringLoader())
					.strictVariables(true)
					.syntax(customSyntax)
					.build()

			val className = data.name.replace(CLASS_NAME_PATTERN, "")
			val (baseIdentifiers, warnings) = metaJson.pebbleParams(ctx, data, defModule, params)
			val identifiers = baseIdentifiers + (KEY_CLASS_NAME to className)

			if (warnings.isNotEmpty()) {
				warn(ctx, R.string.template_exec_warn_identifiers, warnings.joinToString(System.lineSeparator()))
			}

			val packageName =
				resolveString(
					metaJson.parameters
						?.required
						?.packageName
						?.identifier,
					KEY_PACKAGE_NAME,
				)

			for (entry in zip.entries()) {
				if (!entry.name.startsWith("$basePath/")) continue
				if (entry.name == "$basePath/") continue
				if (entry.name.startsWith("$basePath/$META_FOLDER/")) continue

				if ((metaJson.parameters?.optional?.language != null) &&
					(data.language != null) &&
					shouldSkipFile(
						entry.name.removeSuffix(TEMPLATE_EXTENSION),
						safeLanguageName(data.language),
					)
				) {
					continue
				}

				val normalized = filterAndNormalizeZipEntry(entry.name, flags) ?: continue

				val relativePath =
					normalized
						.removePrefix("$basePath/")
						.replace(packageName.value, defModule.packageName.replace(".", "/"))
						.replace(KEY_CLASS_NAME, className)

				val outFile = File(projectDir, relativePath.removeSuffix(TEMPLATE_EXTENSION)).canonicalFile

				if (!outFile.toPath().startsWith(projectRoot.toPath())) {
					warn(ctx, R.string.template_exec_warn_suspicious_entry, entry.name)
					continue
				}

				if (entry.isDirectory) {
					outFile.mkdirs()
				} else {
					processEntry(ctx, zip, entry, outFile, pebbleEngine, identifiers)
				}
			}
		}
	}

	private fun processEntry(
		ctx: Context,
		zip: ZipFile,
		entry: ZipEntry,
		outFile: File,
		pebbleEngine: PebbleEngine,
		identifiers: Map<String, Any>,
	) {
		try {
			outFile.parentFile?.mkdirs()

			if (entry.name.endsWith(TEMPLATE_EXTENSION)) {
				renderTemplateEntry(ctx, zip, entry, outFile, pebbleEngine, identifiers)
			} else {
				copyBinaryEntry(ctx, zip, entry, outFile)
			}
		} catch (e: TemplateExecutionException) {
			throw e
		} catch (e: Exception) {
			throw e.wrap(ctx, R.string.template_exec_error_process, entry.name, e.toString())
		}
	}

	private fun renderTemplateEntry(
		ctx: Context,
		zip: ZipFile,
		entry: ZipEntry,
		outFile: File,
		pebbleEngine: PebbleEngine,
		identifiers: Map<String, Any>,
	) {
		info(ctx, R.string.template_exec_info_processing, entry.name)

		val content =
			try {
				zip.getInputStream(entry).bufferedReader().use { it.readText() }
			} catch (e: Exception) {
				throw e.wrap(ctx, R.string.template_exec_error_read_fail, entry.name)
			}

		val template =
			try {
				pebbleEngine.getTemplate(content)
			} catch (e: PebbleException) {
				throw e.wrap(
					ctx,
					R.string.template_exec_error_parse_line,
					entry.name,
					e.lineNumber,
					e.message,
				)
			} catch (e: Exception) {
				throw e.wrap(ctx, R.string.template_exec_error_parse, entry.name)
			}

		val writer = StringWriter()
		try {
			template.evaluate(writer, identifiers)
		} catch (e: PebbleException) {
			throw e.wrap(
				ctx,
				R.string.template_exec_error_evaluate_line,
				entry.name,
				e.lineNumber,
				e.message,
			)
		} catch (e: Exception) {
			throw e.wrap(ctx, R.string.template_exec_error_evaluate, entry.name, e.toString())
		}

		try {
			outFile.writeText(writer.toString(), Charsets.UTF_8)
		} catch (e: Exception) {
			throw e.wrap(ctx, R.string.template_exec_error_write, outFile.absolutePath, e.toString())
		}
	}

	private fun copyBinaryEntry(
		ctx: Context,
		zip: ZipFile,
		entry: ZipEntry,
		outFile: File,
	) {
		try {
			zip.getInputStream(entry).use { input ->
				outFile.outputStream().use { output ->
					input.copyTo(output)
				}
			}
		} catch (e: Exception) {
			throw e.wrap(ctx, R.string.template_exec_error_copy, entry.name, e.toString())
		}
	}

	private fun keystore(executor: RecipeExecutor) {
		val storeSrc = Environment.KEYSTORE_RELEASE
		val storeDest = File(data.projectDir, Environment.KEYSTORE_RELEASE_NAME)
		if (storeSrc.exists()) {
			executor.copy(storeSrc, storeDest)
		}

		val propsSrc = Environment.KEYSTORE_PROPERTIES
		val propsDest = File(data.projectDir, Environment.KEYSTORE_PROPERTIES_NAME)
		if (propsSrc.exists()) {
			executor.copy(propsSrc, propsDest)
		}
	}

	private fun shouldSkipFile(
		name: String,
		language: String,
	): Boolean {
		// If language is Kotlin, skip .java files
		// If language is Java, skip .kt files
		val ext = name.substringAfterLast('.', "").lowercase()
		return when (language.lowercase()) {
			LANGUAGE_KOTLIN -> ext == FILE_EXT_JAVA
			LANGUAGE_JAVA -> ext == FILE_EXT_KOTLIN
			else -> false
		}
	}

	private fun safeLanguageName(language: Language?): String = language?.name?.lowercase() ?: ""

	private fun safeMinSdkApi(minSdk: Sdk?): String = minSdk?.api?.toString() ?: ""

	private fun TemplateJson.pebbleParams(
		ctx: Context,
		data: ProjectTemplateData,
		defModule: ModuleTemplateData,
		params: MutableMap<String, Parameter<*>>,
	): Pair<Map<String, Any>, List<String>> {
		val warnings = mutableListOf<String>()

		val appName = resolveString(parameters?.required?.appName?.identifier, KEY_APP_NAME)
		if (appName.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_appname,
					KEY_APP_NAME,
				)
		}

		val packageName = resolveString(parameters?.required?.packageName?.identifier, KEY_PACKAGE_NAME)
		if (packageName.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_pkgname,
					KEY_PACKAGE_NAME,
				)
		}

		val saveLocation = resolveString(parameters?.required?.saveLocation?.identifier, KEY_SAVE_LOCATION)
		if (saveLocation.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_location,
					KEY_SAVE_LOCATION,
				)
		}

		val language = resolveString(parameters?.optional?.language?.identifier, KEY_LANGUAGE)

		val minSdk = resolveString(parameters?.optional?.minsdk?.identifier, KEY_MIN_SDK)

		val agpVersion = resolveString(system?.agpVersion?.identifier, KEY_AGP_VERSION)
		if (agpVersion.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_agp,
					KEY_AGP_VERSION,
				)
		}

		val kotlinVersion = resolveString(system?.kotlinVersion?.identifier, KEY_KOTLIN_VERSION)
		if (kotlinVersion.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_kotlin,
					KEY_KOTLIN_VERSION,
				)
		}

		val gradleVersion = resolveString(system?.gradleVersion?.identifier, KEY_GRADLE_VERSION)
		if (gradleVersion.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_gradle,
					KEY_GRADLE_VERSION,
				)
		}

		val compileSdk = resolveString(system?.compileSdk?.identifier, KEY_COMPILE_SDK)
		if (compileSdk.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_compilesdk,
					KEY_COMPILE_SDK,
				)
		}

		val targetSdk = resolveString(system?.targetSdk?.identifier, KEY_TARGET_SDK)
		if (targetSdk.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_targetsdk,
					KEY_TARGET_SDK,
				)
		}

		val javaSourceCompat = resolveString(system?.javaSourceCompat?.identifier, KEY_JAVA_SOURCE_COMPAT)
		if (javaSourceCompat.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_javasource_compat,
					KEY_JAVA_SOURCE_COMPAT,
				)
		}

		val javaTargetCompat = resolveString(system?.javaTargetCompat?.identifier, KEY_JAVA_TARGET_COMPAT)
		if (javaTargetCompat.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_javatarget_compat,
					KEY_JAVA_TARGET_COMPAT,
				)
		}

		val javaTarget = resolveString(system?.javaTarget?.identifier, KEY_JAVA_TARGET)
		if (javaTarget.usedDefault) {
			warnings +=
				ctx.getString(
					R.string.template_exec_warn_map_javatarget,
					KEY_JAVA_TARGET,
				)
		}

		val baseMap =
			mapOf(
				appName.value to data.name,
				packageName.value to defModule.packageName,
				saveLocation.value to data.projectDir.toString(),
				language.value to safeLanguageName(data.language),
				minSdk.value to safeMinSdkApi(defModule.versions.minSdk),
				agpVersion.value to ANDROID_GRADLE_PLUGIN_VERSION,
				kotlinVersion.value to KOTLIN_VERSION,
				gradleVersion.value to data.version.gradle,
				compileSdk.value to
					defModule.versions.compileSdk.api
						.toString(),
				targetSdk.value to
					defModule.versions.targetSdk.api
						.toString(),
				javaSourceCompat.value to defModule.versions.javaSource(),
				javaTargetCompat.value to defModule.versions.javaTarget(),
				javaTarget.value to defModule.versions.javaTarget,
			)

		val map =
			baseMap +
				params.mapValues { (_, param) ->
					param.value ?: ""
				}

		return map to warnings
	}

	data class ResolvedParam<T>(
		val value: T,
		val usedDefault: Boolean,
	)

	private fun resolveString(
		value: String?,
		default: String,
	): ResolvedParam<String> =
		if (value.isNullOrBlank()) {
			ResolvedParam(default, true)
		} else {
			ResolvedParam(value, false)
		}

	private fun resolveBoolean(
		raw: Boolean?,
		default: Boolean,
	): ResolvedParam<Boolean> =
		if (raw == null) {
			ResolvedParam(default, true)
		} else {
			ResolvedParam(raw, false)
		}

	private fun filterAndNormalizeZipEntry(
		entryName: String,
		flags: Map<String, Boolean>,
	): String? {
		val parts = entryName.split(File.separator).filter { it.isNotEmpty() }
		if (parts.isEmpty()) return null

		val normalizedParts = mutableListOf<String>()

		for (part in parts) {
			when (flags[part]) {
				null -> {
					normalizedParts.add(part)
				}

				true -> { }

				false -> {
					return null
				}
			}
		}

		return normalizedParts.joinToString(File.separator)
	}

	private fun warn(msg: String) {
		hasErrorsWarnings = true
		log.warn(msg)
	}

	private fun warn(
		context: Context,
		@StringRes resId: Int,
		vararg args: Any?,
	) {
		val msg = context.getString(resId, *args)
		warn(msg)
	}

	private fun info(msg: String) {
		log.info(msg)
	}

	private fun info(
		context: Context,
		@StringRes resId: Int,
		vararg args: Any?,
	) {
		val msg = context.getString(resId, *args)
		info(msg)
	}

	private fun error(
		msg: String,
		e: Exception,
	) {
		hasErrorsWarnings = true
		log.error(msg, e)
	}

	private fun Exception.wrap(
		context: Context,
		@StringRes resId: Int,
		vararg args: Any?,
	): TemplateExecutionException {
		val msg = context.getString(resId, *args)
		return TemplateExecutionException(msg, this)
	}

	@SuppressLint("SetWorldReadable")
	private fun loadExtensionFromArchive(
		zip: ZipFile,
		entry: ZipEntry,
		context: Context,
	): List<Extension> {
		val tempJar = File.createTempFile("ext_", ".jar", context.codeCacheDir)

		try {
			zip.getInputStream(entry).use { input ->
				tempJar.outputStream().use { output ->
					input.copyTo(output)
				}
			}
		} catch (e: Exception) {
			error("Failed to extract ${entry.name} to ${tempJar.absolutePath}", e)
			return emptyList()
		}

		try {
			tempJar.setReadable(true, false)
			tempJar.setWritable(false)
			tempJar.setExecutable(false)
		} catch (e: SecurityException) {
			warn("Could not adjust permissions on ${tempJar.absolutePath} $e")
		}

		// basePath comes from the archive's templates.json - keep the dex-opt
		// dir contained the same way outFile is checked against projectRoot.
		val dexOptRoot = File(Environment.TEMPLATES_DIR, DEX_OPT_FOLDER).canonicalFile
		val optimizedDir = File(dexOptRoot, basePath).canonicalFile

		if (!optimizedDir.toPath().startsWith(dexOptRoot.toPath())) {
			error(
				"Template basePath escapes templates dir: $basePath",
				IllegalArgumentException(basePath),
			)
			return emptyList()
		}

		if (!optimizedDir.exists() && !optimizedDir.mkdirs()) {
			error(
				"Failed to create optimized dex directory: ${optimizedDir.absolutePath}",
				IllegalStateException("mkdirs() failed for ${optimizedDir.absolutePath}"),
			)
			return emptyList()
		}

		val classLoader =
			try {
				DexClassLoader(
					tempJar.absolutePath,
					optimizedDir.absolutePath,
					null,
					context.classLoader,
				)
			} catch (e: Exception) {
				error("Failed to create DexClassLoader for ${entry.name}", e)
				return emptyList()
			}

		val serviceLoader =
			try {
				ServiceLoader.load(Extension::class.java, classLoader)
			} catch (e: Throwable) {
				error(
					"ServiceLoader failed for ${entry.name}",
					Exception("ServiceLoader failed", e),
				)
				return emptyList()
			}

		val extensions = mutableListOf<Extension>()

		try {
			for (ext in serviceLoader) {
				try {
					log.debug("Loading ${ext::class.java.name}")
					extensions += ext
				} catch (e: Throwable) {
					error(
						"Failed to instantiate extension from ${entry.name}",
						Exception("Failed to instantiate extension", e),
					)
				}
			}
		} catch (e: Throwable) {
			error(
				"ServiceLoader iteration failed for ${entry.name}",
				Exception("ServiceLoader iteration failed", e),
			)
		}

		return extensions
	}
}
