package com.itsaky.androidide.gradle.sqlite

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Fails the build, before anything is compiled or rewritten, when the variant's runtime classpath
 * lacks what the rewritten classes load: requery itself, androidx.sqlite (whose interfaces requery
 * implements) and androidx.core (whose CancellationSignal requery's API takes, and the rewrite
 * converts android's signal to). requery reaches an app as a file dependency from `libs/`, which
 * brings none of its own dependencies.
 */
abstract class CheckSqliteRewriteClasspathTask : DefaultTask() {
	@get:Input
	abstract val variantName: Property<String>

	@get:Classpath
	abstract val runtimeClasspath: ConfigurableFileCollection

	/** Written on success, so an unchanged classpath is not scanned again. */
	@get:OutputFile
	abstract val stamp: RegularFileProperty

	@TaskAction
	fun check() {
		val files = runtimeClasspath.files
		val missing = REQUIRED.filter { (_, entry) -> files.none { contains(it, entry) } }
		if (missing.isNotEmpty()) {
			throw GradleException(message(variantName.get(), missing.map { it.first }))
		}
		stamp.get().asFile.apply {
			parentFile.mkdirs()
			writeText("ok")
		}
	}

	private fun contains(
		file: File,
		entry: String,
	): Boolean =
		when {
			file.isDirectory -> File(file, entry).isFile
			file.isFile -> runCatching { ZipFile(file).use { it.getEntry(entry) != null } }.getOrDefault(false)
			else -> false
		}

	companion object {
		internal const val REQUERY_ENTRY = "io/requery/android/database/sqlite/SQLiteDatabase.class"
		internal const val ANDROIDX_SQLITE_ENTRY = "androidx/sqlite/db/SupportSQLiteDatabase.class"
		internal const val ANDROIDX_CORE_ENTRY = "${SqliteRewriteRules.CORE_SIGNAL}.class"

		private val REQUIRED =
			listOf(
				"requery's SQLiteDatabase ($REQUERY_ENTRY)" to REQUERY_ENTRY,
				"androidx.sqlite's SupportSQLiteDatabase ($ANDROIDX_SQLITE_ENTRY)" to ANDROIDX_SQLITE_ENTRY,
				"androidx.core's CancellationSignal ($ANDROIDX_CORE_ENTRY)" to ANDROIDX_CORE_ENTRY,
			)

		internal fun message(
			variant: String,
			missing: List<String>,
		) = "cogo.sqliteDebugger=true moves the app to requery, but the runtime classpath of variant '$variant' has no " +
			missing.joinToString(" and no ") +
			". requery's AAR comes from the project's libs/ directory as a file dependency, which brings none of its own " +
			"dependencies: add requery's AAR to libs/ (implementation(files(\"libs/sqlite-android.aar\"))), androidx.sqlite " +
			"(implementation(\"androidx.sqlite:sqlite:<version>\"), or a library that depends on it, such as Room or WorkManager) " +
			"and androidx.core (implementation(\"androidx.core:core:<version>\"), or a library that depends on it, such as AppCompat). " +
			"Remove cogo.sqliteDebugger from gradle.properties to build without them."

		/** The tasks of a variant that must not start before the check: compiling and the rewrite itself. */
		internal fun guardedTaskNames(capitalizedVariant: String) =
			setOf(
				"compile${capitalizedVariant}JavaWithJavac",
				"compile${capitalizedVariant}Kotlin",
				"transform${capitalizedVariant}ClassesWithAsm",
			)
	}
}
