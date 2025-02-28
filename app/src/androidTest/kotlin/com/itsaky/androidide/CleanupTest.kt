package com.itsaky.androidide

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Test
import java.io.FileInputStream
import java.io.IOException

class CleanupTest {
    companion object {
        @JvmStatic
        @AfterClass
        fun cleanup() {
            try {
                val command = "rm -rf /sdcard/AndroidIDEProjects"
                executeShellCommand(command)
                println("Cleanup executed successfully!")
            } catch (e: IOException) {
                println("Failed to execute cleanup")
                e.printStackTrace()
            }
        }

        private fun executeShellCommand(command: String): String {
            val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
            val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
            val fileInputStream = FileInputStream(pfd.fileDescriptor)
            return fileInputStream.bufferedReader().use { it.readText() }
        }
    }

    @Test
    fun dummyTest() {
        // This test ensures the class is executed
    }
}
