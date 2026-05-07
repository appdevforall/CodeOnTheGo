/*
 * Logcat assertions for plugin instrumented tests.
 *
 * The two runtime failure modes that leak through static checks (and therefore
 * through `./gradlew assembleDebug`) are:
 *
 *   - `Resources$NotFoundException` — fragment inflater lacks the plugin's
 *     resource context (i.e., the fragment didn't wrap inflater via
 *     `PluginFragmentHelper.getPluginInflater`).
 *   - `ActivityNotFoundException` — plugin tried to launch a `<activity>`
 *     declared in its own manifest, which never landed in the host's
 *     `PackageManager` because plugin APKs load via `DexClassLoader`.
 *
 * Both Forms (ADFA-2435) and Maps (ADFA-2436) shipped fixes for these on
 * 2026-05-07 — these helpers are the merge-gate that prevents regressions.
 *
 * Usage:
 *
 * ```
 * val watcher = LogcatWatcher.start()
 * // ... drive UI ...
 * watcher.assertNoFatalPluginErrors()
 * ```
 */
package com.itsaky.androidide.plugins.testsupport

import android.app.Instrumentation
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Captures a snapshot of logcat from a starting point and exposes
 * assertion helpers for patterns that indicate plugin runtime failures.
 *
 * Logcat is a circular buffer; we tag the start with a unique marker so we
 * can isolate output produced *during* the test from prior noise.
 */
class LogcatWatcher private constructor(private val markerTag: String) {

    /**
     * Read logcat content from after the start marker. Uses `-d` (dump &
     * exit) so this returns synchronously.
     */
    fun dumpSinceStart(): String {
        val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
        val pfd = instr.uiAutomation.executeShellCommand("logcat -d -v brief")
        val raw = BufferedReader(InputStreamReader(FileInputStream(pfd.fileDescriptor)))
            .use { it.readText() }
        // Slice to lines emitted after our marker line. If the marker rolled
        // out of the buffer (unlikely for a short test), fall back to the
        // whole dump — the assertion still works, just less precise.
        val markerIdx = raw.indexOf(markerTag)
        return if (markerIdx >= 0) raw.substring(markerIdx) else raw
    }

    /**
     * Asserts no plugin-fatal exceptions in the captured window. Failure
     * messages embed a snippet of the matching log line for diagnosis.
     */
    fun assertNoFatalPluginErrors() {
        val log = dumpSinceStart()
        for (pattern in FATAL_PATTERNS) {
            val match = pattern.find(log)
            if (match != null) {
                // Pull ~3 lines of context around the match for diagnosis.
                val start = (log.lastIndexOf('\n', match.range.first) + 1).coerceAtLeast(0)
                val end = log.indexOf('\n', match.range.last + 1).let {
                    if (it < 0) log.length else it
                }
                val snippet = log.substring(start, end)
                throw AssertionError(
                    "Plugin runtime failure detected in logcat: ${match.value}\n" +
                        "Context: $snippet"
                )
            }
        }
    }

    companion object {
        // Patterns that indicate the runtime bugs Forms+Maps shipped with on
        // 2026-05-07. Add new patterns here when new classes of runtime
        // plugin failures are discovered.
        private val FATAL_PATTERNS = listOf(
            Regex("""android\.content\.res\.Resources${'$'}NotFoundException"""),
            Regex("""android\.content\.ActivityNotFoundException"""),
        )

        /**
         * Begin a watch window. Emits a unique marker into logcat that
         * [dumpSinceStart] uses as a slice point. Each test should start a
         * fresh watcher in `@Before` (or at the top of the test body).
         */
        fun start(testTag: String = "PluginSmoke"): LogcatWatcher {
            val markerTag = "$testTag-${System.nanoTime()}"
            // Inject a marker line at known severity so logcat -d picks it up.
            val instr = InstrumentationRegistry.getInstrumentation()
            instr.uiAutomation.executeShellCommand(
                "log -t $markerTag start"
            )
            return LogcatWatcher(markerTag)
        }
    }
}

/**
 * Convenience for tests that just want a single assertion at the end:
 *
 * ```
 * @Test fun smoke() {
 *     val watcher = LogcatWatcher.start()
 *     // ... drive UI ...
 *     watcher.assertNoFatalPluginErrors()
 * }
 * ```
 */
fun assertNoFatalPluginErrorsInLogcat(watcher: LogcatWatcher) {
    val log = watcher.dumpSinceStart()
    assertThat(log).doesNotContain("Resources\$NotFoundException")
    assertThat(log).doesNotContain("ActivityNotFoundException")
}
