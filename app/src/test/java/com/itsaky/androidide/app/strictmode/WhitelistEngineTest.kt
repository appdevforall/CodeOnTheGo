package com.itsaky.androidide.app.strictmode

import android.app.Application
import android.os.strictmode.DiskReadViolation
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WhitelistEngineTest {

    @Test
    fun `should ALLOW firebase readAutoDataCollectionEnabled violation`() {
        val mockViolation = mock(DiskReadViolation::class.java)
        val rawStack = listOf(
            "android.os.StrictMode\$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1766)",
            "android.app.SharedPreferencesImpl.awaitLoadedLocked(SharedPreferencesImpl.java:283)",
            "android.app.SharedPreferencesImpl.contains(SharedPreferencesImpl.java:361)",
            "com.google.firebase.internal.DataCollectionConfigStorage.readAutoDataCollectionEnabled(DataCollectionConfigStorage.java:102)",
            "com.google.firebase.internal.DataCollectionConfigStorage.<init>(DataCollectionConfigStorage.java:48)",
            "com.google.firebase.FirebaseApp.lambda\$new\$0\$com-google-firebase-FirebaseApp(FirebaseApp.java:448)",
            "com.google.firebase.FirebaseApp\$\$ExternalSyntheticLambda0.get(D8\$\$SyntheticClass:0)",
            "com.google.firebase.components.Lazy.get(Lazy.java:53)",
            "com.google.firebase.FirebaseApp.isDataCollectionDefaultEnabled(FirebaseApp.java:371)",
            "com.google.firebase.analytics.connector.AnalyticsConnectorImpl.getInstance(play-services-measurement-api@@22.1.2:31)",
            "com.google.firebase.FirebaseApp\$UserUnlockReceiver.onReceive(FirebaseApp.java:672)"
        )

        doReturn(parseStackTrace(rawStack)).`when`(mockViolation).stackTrace

        val violationWrapper = ViolationDispatcher.Violation(
            violation = mockViolation,
            type = ViolationDispatcher.ViolationType.THREAD
        )

        val decision = WhitelistEngine.evaluate(violationWrapper)

        if (decision is WhitelistEngine.Decision.Crash) {
            throw RuntimeException("StrictMode violation detected", mockViolation)
        }
    }

    private fun parseStackTrace(lines: List<String>): Array<StackTraceElement> {
        val regex = Regex("""^(.+)\.([^.]+)\((.+):(\d+)\)$""")

        return lines.map { line ->
            val match = regex.find(line.trim())
            if (match != null) {
                val (className, methodName, fileName, lineNumber) = match.destructured
                StackTraceElement(className, methodName, fileName, lineNumber.toInt())
            } else {
                StackTraceElement("Unknown", "Unknown", null, -1)
            }
        }.toTypedArray()
    }
}