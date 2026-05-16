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
        val destination = File(Environment.getExternalStorageDirectory(), destinationRelativePath)

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

    private companion object {
        const val SOURCE_RELATIVE_PATH = "home/.gradle/caches/modules-2/files-2.1"
        const val ARG_DESTINATION_RELATIVE_PATH = "androidide.exportCache.destination"
        const val DEFAULT_DESTINATION_RELATIVE_PATH = "CodeOnTheGoProjects/gradle-cache/modules-2/files-2.1"
    }
}
