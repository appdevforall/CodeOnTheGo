package com.itsaky.androidide

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CleanupTest {

    @Test
    fun performCleanup() {
        try {
            val command = "rm -rf /sdcard/AndroidIDEProjects"
            executeShellCommand(command)
            println("Cleanup executed successfully!")
        } catch (e: IOException) {
            println("Failed to execute cleanup: ${e.message}")
            // Don't fail the test - cleanup is best effort
        }
    }

    private fun executeShellCommand(command: String): String {
        val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val fileInputStream = FileInputStream(pfd.fileDescriptor)
        return fileInputStream.bufferedReader().use { it.readText() }
    }
}