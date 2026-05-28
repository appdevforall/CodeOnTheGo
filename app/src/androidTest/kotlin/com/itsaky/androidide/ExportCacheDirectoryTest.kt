package com.itsaky.androidide

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportCacheDirectoryTest {

    @Test
    fun exportGradleModuleCacheBeforeConnectedTestCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()

        val destinationRelativePath =
            args.getString(ARG_DESTINATION_RELATIVE_PATH) ?: DEFAULT_DESTINATION_RELATIVE_PATH

        val source = File(context.filesDir, SOURCE_RELATIVE_PATH)
        val externalBase = File(Environment.getExternalStorageDirectory(), EXPORT_BASE_DIRECTORY).canonicalFile
        val destination = resolveSafeDestination(externalBase, destinationRelativePath)

        assumeTrue("Source does not exist: ${source.absolutePath}", source.exists())
        assumeTrue("Source is not a directory: ${source.absolutePath}", source.isDirectory)

        destination.deleteRecursively()
        destination.parentFile?.mkdirs()

        assertTrue(
            "Failed to copy ${source.absolutePath} to ${destination.absolutePath}",
            source.copyRecursively(target = destination, overwrite = true),
        )
        assertTrue("Destination does not exist: ${destination.absolutePath}", destination.exists())
    }

    @Test
    fun exportGeneratedGradleApiJarWhenRequested() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val version = args.getString(ARG_GRADLE_API_VERSION)

        assumeTrue("No Gradle API version requested", !version.isNullOrBlank())
        require(!version!!.contains(File.separatorChar)) {
            "Gradle API version must not contain path separators: $version"
        }

        val destinationRelativePath =
            args.getString(ARG_GRADLE_API_DESTINATION_RELATIVE_PATH)
                ?: DEFAULT_GRADLE_API_DESTINATION_RELATIVE_PATH

        val source =
            File(
                context.filesDir,
                "$GRADLE_CACHE_RELATIVE_PATH/$version/generated-gradle-jars/gradle-api-$version.jar",
            )
        val externalBase = File(Environment.getExternalStorageDirectory(), EXPORT_BASE_DIRECTORY).canonicalFile
        val destinationDir = resolveSafeDestination(externalBase, destinationRelativePath)
        val destination = File(destinationDir, source.name)

        assertTrue("Source does not exist: ${source.absolutePath}", source.exists())
        assertTrue("Source is not a file: ${source.absolutePath}", source.isFile)

        destination.parentFile?.mkdirs()
        source.copyTo(target = destination, overwrite = true)

        assertTrue("Destination does not exist: ${destination.absolutePath}", destination.exists())
    }

    private fun resolveSafeDestination(base: File, relativePath: String): File {
        require(relativePath.isNotBlank()) {
            "Destination path must be a non-blank relative path"
        }
        require(!File(relativePath).isAbsolute) {
            "Destination path must be relative"
        }

        val destination = File(base, relativePath.removePrefix("$EXPORT_BASE_DIRECTORY/")).canonicalFile
        require(destination.toPath().startsWith(base.toPath())) {
            "Destination must stay under ${base.absolutePath}: ${destination.absolutePath}"
        }
        return destination
    }

    private companion object {
        const val SOURCE_RELATIVE_PATH = "home/.gradle/caches/modules-2/files-2.1"
        const val GRADLE_CACHE_RELATIVE_PATH = "home/.gradle/caches"
        const val ARG_DESTINATION_RELATIVE_PATH = "androidide.exportCache.destination"
        const val ARG_GRADLE_API_VERSION = "androidide.exportGradleApi.version"
        const val ARG_GRADLE_API_DESTINATION_RELATIVE_PATH = "androidide.exportGradleApi.destination"
        const val EXPORT_BASE_DIRECTORY = "CodeOnTheGoProjects"
        const val DEFAULT_DESTINATION_RELATIVE_PATH = "CodeOnTheGoProjects/gradle-cache/modules-2/files-2.1"
        const val DEFAULT_GRADLE_API_DESTINATION_RELATIVE_PATH = "CodeOnTheGoProjects/gradle-api"
    }
}
