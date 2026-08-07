/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.lsp.java.loader

import android.content.Context
import com.itsaky.androidide.lsp.java.api.IJavaCompilerSession
import com.itsaky.androidide.lsp.java.api.IJavaCompilerSessionFactory
import com.itsaky.androidide.projects.api.Workspace
import dalvik.system.DexClassLoader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Extracts the javac carrier APK from assets and loads it via [DexClassLoader] on first use,
 * so the vendored javac fork (ADFA-5053) is never resident in the main app dex or
 * classloaded until a real `.java`-file interaction actually needs it. Mirrors
 * `KotlinCompilerLoader`'s construction (ADR 0011), which mirrors `PluginLoader`'s.
 */
class JavaCompilerLoader(
	private val context: Context,
) {
	@Volatile
	private var session: IJavaCompilerSession? = null

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
			logger.info("Extracting Java compiler carrier APK to {}", dest)
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
	 * `JavaCompilerService`/`SourceFileManager` construction it replaces was already
	 * blocking when it ran inside `ensureProjectReset()`.
	 */
	fun getOrCreateSession(workspace: Workspace): IJavaCompilerSession {
		session?.let { return it }

		synchronized(this) {
			session?.let { return it }

			val optimizedDir = File(context.codeCacheDir, "java_compiler_dex").apply { mkdirs() }
			val classLoader =
				DexClassLoader(
					carrierApk.absolutePath,
					optimizedDir.absolutePath,
					null,
					this::class.java.classLoader,
				)

			val factory =
				classLoader
					.loadClass(FACTORY_CLASS_NAME)
					.getDeclaredConstructor()
					.newInstance() as IJavaCompilerSessionFactory

			val created = factory.create(workspace)
			session = created
			return created
		}
	}

	fun currentSession(): IJavaCompilerSession? = session

	fun close() {
		session?.close()
		session = null
	}

	companion object {
		private const val CARRIER_DIR_NAME = "java_compiler"
		private const val CARRIER_APK_FILE_NAME = "java-compiler-carrier.apk"
		private const val CARRIER_APK_ASSET_PATH = "data/common/$CARRIER_APK_FILE_NAME"
		private const val FACTORY_CLASS_NAME =
			"com.itsaky.androidide.lsp.java.compiler.JavaCompilerSessionFactoryImpl"
		private val logger = LoggerFactory.getLogger(JavaCompilerLoader::class.java)
	}
}
