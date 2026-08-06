package com.itsaky.androidide.lsp.kotlin.loader

import android.content.Context
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilerSession
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilerSessionFactory
import com.itsaky.androidide.projects.api.Workspace
import dalvik.system.DexClassLoader
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Extracts the Kotlin Analysis API carrier APK from assets and loads it via
 * [DexClassLoader] on first use, so the ~28MB Analysis API dependency graph
 * (ADFA-5010) is never resident in the main app dex or classloaded until a Kotlin
 * file is actually opened. Mirrors `PluginLoader`'s DexClassLoader construction.
 */
class KotlinCompilerLoader(
	private val context: Context,
) {
	@Volatile
	private var session: IKotlinCompilerSession? = null

	private val carrierApk: File by lazy { extractCarrierApk() }

	private fun extractCarrierApk(): File {
		val dir = context.getDir(CARRIER_DIR_NAME, Context.MODE_PRIVATE)
		val dest = File(dir, CARRIER_APK_FILE_NAME)
		val markerFile = File(dir, "$CARRIER_APK_FILE_NAME.marker")

		// The main APK's own mtime as a cheap "has the app been updated/reinstalled since we
		// last extracted" marker -- avoids re-extracting on every process start.
		val currentMarker = File(context.applicationInfo.sourceDir).lastModified().toString()
		val previousMarker = runCatching { markerFile.readText() }.getOrNull()

		if (!dest.exists() || previousMarker != currentMarker) {
			logger.info("Extracting Kotlin compiler carrier APK to {}", dest)
			context.assets.open(CARRIER_APK_ASSET_PATH).use { input ->
				dest.outputStream().use { output -> input.copyTo(output) }
			}
			markerFile.writeText(currentMarker)
		}

		return dest
	}

	/**
	 * Returns the current session, creating one (extracting the carrier APK and
	 * `DexClassLoader`-loading it if needed) on first call. Blocking, like the
	 * `Compiler` construction it replaces was already blocking when it ran eagerly
	 * inside `setupWithProject`.
	 */
	fun getOrCreateSession(
		workspace: Workspace,
		jdkHome: Path,
		jdkRelease: Int,
		jdkVersionString: String,
		languageClient: ILanguageClient?,
	): IKotlinCompilerSession {
		session?.let { return it }

		synchronized(this) {
			session?.let { return it }

			val optimizedDir = File(context.codeCacheDir, "kotlin_compiler_dex").apply { mkdirs() }
			val classLoader =
				DexClassLoader(
					carrierApk.absolutePath,
					optimizedDir.absolutePath,
					null,
					this::class.java.classLoader,
				)

			// The Analysis API/IntelliJ-platform machinery inside the carrier dex relies on
			// context-classloader-default `ServiceLoader.load(...)` for its own internal
			// extension-point wiring; without this, those lookups silently return empty
			// (not an exception) against the wrong (parent) classloader.
			val previousContextClassLoader = Thread.currentThread().contextClassLoader
			Thread.currentThread().contextClassLoader = classLoader
			try {
				val factory =
					classLoader
						.loadClass(FACTORY_CLASS_NAME)
						.getDeclaredConstructor()
						.newInstance() as IKotlinCompilerSessionFactory

				val created =
					factory.create(
						workspace = workspace,
						intellijPluginRoot = carrierApk.toPath(),
						jdkHome = jdkHome,
						jdkRelease = jdkRelease,
						jdkVersionString = jdkVersionString,
						languageClient = languageClient,
					)

				session = created
				return created
			} finally {
				Thread.currentThread().contextClassLoader = previousContextClassLoader
			}
		}
	}

	fun currentSession(): IKotlinCompilerSession? = session

	fun close() {
		session?.close()
		session = null
	}

	companion object {
		private const val CARRIER_DIR_NAME = "kotlin_compiler"
		private const val CARRIER_APK_FILE_NAME = "kotlin-compiler-carrier.apk"
		private const val CARRIER_APK_ASSET_PATH = "data/common/$CARRIER_APK_FILE_NAME"
		private const val FACTORY_CLASS_NAME =
			"com.itsaky.androidide.lsp.kotlin.compiler.KotlinCompilerSessionFactoryImpl"
		private val logger = LoggerFactory.getLogger(KotlinCompilerLoader::class.java)
	}
}
