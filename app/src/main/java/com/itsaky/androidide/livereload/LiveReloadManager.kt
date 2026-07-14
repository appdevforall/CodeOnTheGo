/*
 * ADFA-4128 — on-device live-reload loop (prototype / spike glue).
 *
 * NOT part of the shipped IDE. This wires CoGo's editor into the mini-stubby
 * "Son of Stubby" shell so a source edit hot-reloads on-device WITHOUT a Gradle
 * assemble + PackageInstaller + Play-Protect cycle. It implements the copy/deploy
 * half of the doc's "proposed fast loop":
 *
 *   CoGo editor save / Claude-pushed file
 *        -> FileObserver (0.1 s debounce, THIS class)
 *        -> POST /build to the on-device warm compile daemon (KotlinCompileService)
 *        -> daemon compiles/dexes (or aapt2-relinks) + serves /payload
 *        -> the shell long-polls /payload, verifies the digest, hot-reloads (~40 ms).
 *
 * The daemon runs as a subprocess of CoGo (debuggable build) using CoGo's bundled
 * JDK + d8 + aapt2. ALL toolchain paths live in the device-side run_daemon.sh that
 * the staging step writes — so a path fix never needs a CoGo rebuild. Live-reload is
 * simply "off" on any device where that script hasn't been staged.
 */
package com.itsaky.androidide.livereload

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object LiveReloadManager {

  private const val TAG = "MiniStubbyLR"
  private const val BUILD_URL = "http://127.0.0.1:8378/build"
  private const val DEBOUNCE_MS = 100L

  /** The installed-once shell app that hosts the hot-reloaded payload. */
  const val SHELL_PACKAGE = "com.adfa.ministubby.host"

  private val main = Handler(Looper.getMainLooper())
  private val watchers = ArrayList<FileObserver>()
  private var daemon: Process? = null
  @Volatile private var started = false
  private var pendingKind: String? = null
  private val pendingPaths = LinkedHashSet<String>()   // changed .kt abs paths (for incremental)

  /** The daemon launch script the staging step drops into files/mstc/. */
  private fun runScript(context: Context) = File(File(context.filesDir, "mstc"), "run_daemon.sh")

  /**
   * True once the on-device compile daemon has been staged. Gates the Run button's
   * live-reload behavior: on a plain device (no staging) Run does its normal
   * Gradle-assemble + install, untouched.
   */
  fun isStaged(context: Context): Boolean = runScript(context).exists()

  /**
   * A project opts into live-reload with a `.livereload` marker in its root AND the
   * daemon being staged on this device. Anything else takes the normal Run path.
   */
  fun isLiveReloadProject(context: Context, projectDir: File): Boolean =
    isStaged(context) && File(projectDir, ".livereload").exists()

  // ---- full-build vs fast-loop decision (the manifest/dependency boundary) ----

  /** Signature of the last full Gradle build's manifest + Gradle inputs, per project. */
  private val lastFullBuildSig = HashMap<String, String>()

  /**
   * True when Run must do a FULL Gradle build + install rather than the fast loop:
   * the first Run of a project, or when a manifest / Gradle / version-catalog file
   * changed since the last full build. Those cross the OS's pre-code boundary
   * (manifest-bound features, dependency provisioning) and can't be hot-loaded.
   */
  fun needsFullBuild(projectDir: File): Boolean {
    val prev = lastFullBuildSig[projectDir.absolutePath] ?: return true
    return prev != buildInputsSignature(projectDir)
  }

  /** Snapshot the manifest + Gradle inputs so later edits to them re-trigger a full build. */
  fun recordFullBuild(projectDir: File) {
    lastFullBuildSig[projectDir.absolutePath] = buildInputsSignature(projectDir)
  }

  /** mtime signature of every manifest / Gradle / catalog file under the project. */
  private fun buildInputsSignature(projectDir: File): String {
    if (!projectDir.exists()) return ""
    val names = setOf(
      "AndroidManifest.xml", "build.gradle", "build.gradle.kts",
      "settings.gradle", "settings.gradle.kts", "gradle.properties", "libs.versions.toml",
    )
    return projectDir.walkTopDown()
      .filter { it.isFile && it.name in names && !it.path.contains("${File.separator}build${File.separator}") }
      .sortedBy { it.path }
      .joinToString("|") { "${it.path}:${it.lastModified()}" }
  }

  /**
   * Idempotently launch the warm compile daemon and start watching [srcRoots]
   * (recursively). Safe to call on every Run; a no-op after the first success.
   */
  @Synchronized
  fun ensureStarted(context: Context, projectDir: File, srcRoots: List<File>) {
    if (started) return
    val script = runScript(context)
    if (!script.exists()) {
      Log.i(TAG, "live-reload not staged (${script.path}); leaving Run as-is")
      return
    }
    startDaemon(script, projectDir)
    startWatching(srcRoots)
    started = true
  }

  private fun startDaemon(script: File, projectDir: File) {
    try {
      // Pass the open project's dir so the daemon compiles THIS project, not a baked one.
      val pb = ProcessBuilder("sh", script.absolutePath, projectDir.absolutePath).apply {
        directory(script.parentFile)
        redirectErrorStream(true)
      }
      val p = pb.start()
      daemon = p
      Thread({
        try {
          p.inputStream.bufferedReader().forEachLine { Log.i("MiniStubbyKCS", it) }
        } catch (_: Throwable) { /* process ended */ }
      }, "mstc-daemon-log").apply { isDaemon = true }.start()
      Log.i(TAG, "compile daemon launched: ${script.absolutePath}")
    } catch (t: Throwable) {
      Log.e(TAG, "compile daemon launch failed", t)
    }
  }

  /** Register a (non-recursive) FileObserver per directory under each source root. */
  private fun startWatching(srcRoots: List<File>) {
    for (root in srcRoots) {
      if (!root.exists()) continue
      root.walkTopDown().filter { it.isDirectory }.forEach { dir ->
        val isRes = dir.path.contains("${File.separator}res")
        // The String-path ctor is deprecated (API 29+ prefers File) but works on every
        // API level — and dodges a NewApi lint error at the module's minSdk 28.
        @Suppress("DEPRECATION")
        val obs = object : FileObserver(
          dir.absolutePath,
          FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
            FileObserver.MOVED_FROM or FileObserver.DELETE,
        ) {
          override fun onEvent(event: Int, path: String?) {
            if (path == null || path.startsWith(".")) return   // ignore temp/dotfiles
            val kind =
              if (path.endsWith(".kt") || path.endsWith(".java")) "code"
              else if (isRes) "res"
              else "code"
            val abs = if (path.endsWith(".kt")) File(dir, path).absolutePath else null
            scheduleBuild(kind, abs)
          }
        }
        try { obs.startWatching(); watchers += obs } catch (_: Throwable) { /* skip */ }
      }
      Log.i(TAG, "watching ${root.absolutePath}")
    }
  }

  /**
   * 0.1 s debounce: coalesce a burst of writes (a "save all", or Claude dropping
   * several files) into ONE build. A code change in the window wins over res-only
   * (compile+dex covers resources too via the relink). [path] is the changed .kt
   * absolute path (null for res edits or an explicit Run request), accumulated so
   * the daemon recompiles just those files.
   *
   * The Run button and the file watcher BOTH funnel through here, so a Run (flush →
   * watcher events) collapses into a single build instead of two.
   */
  @Synchronized
  private fun scheduleBuild(kind: String, path: String?) {
    pendingKind = if (pendingKind == "code" || kind == "code") "code" else "res"
    if (path != null) pendingPaths += path
    main.removeCallbacks(fireBuild)
    main.postDelayed(fireBuild, DEBOUNCE_MS)
  }

  private val fireBuild = Runnable {
    val kind: String
    val paths: List<String>
    synchronized(this) {
      kind = pendingKind ?: "code"
      paths = ArrayList(pendingPaths)
      pendingKind = null
      pendingPaths.clear()
    }
    postBuild(kind, paths)
  }

  /**
   * Explicit build request from the Run button (after flushing editors). Goes through
   * the same debounce so it merges with any watcher events from the same flush.
   */
  fun requestBuild(kind: String) = scheduleBuild(kind, null)

  /** POST /build?kind=<kind> with the changed paths as the body (background thread). */
  private fun postBuild(kind: String, paths: List<String>) {
    Thread({
      var c: HttpURLConnection? = null
      try {
        c = (URL("$BUILD_URL?kind=$kind").openConnection() as HttpURLConnection).apply {
          connectTimeout = 3_000
          readTimeout = 60_000            // a cold first compile can take a few seconds
          requestMethod = "POST"
          doOutput = true
        }
        c.outputStream.use { it.write(paths.joinToString("\n").toByteArray()) }
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)
          ?.bufferedReader()?.use { it.readText() }
        Log.i(TAG, "POST /build?kind=$kind (${paths.size} paths) -> $code $body")
      } catch (t: Throwable) {
        Log.w(TAG, "POST /build failed (daemon down?): $t")
      } finally {
        c?.disconnect()
      }
    }, "mstc-build-trigger").start()
  }

  @Synchronized
  fun stop() {
    watchers.forEach { runCatching { it.stopWatching() } }
    watchers.clear()
    daemon?.destroy()
    daemon = null
    started = false
  }
}
