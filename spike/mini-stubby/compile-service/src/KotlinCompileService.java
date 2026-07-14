import com.sun.net.httpserver.HttpServer;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.lang.reflect.Method;

/**
 * ADFA-4128 — Persistent Kotlin compile service.
 *
 * The piece that turns the ~0.5–0.8 s save→rendered projection into a running loop:
 * a LONG-LIVED process that keeps everything expensive warm across every save, so
 * each edit pays only the incremental cost.
 *
 * What it holds warm (paid ONCE at startup, never per-save):
 *   1. A warm JVM with the Kotlin compiler classloaded + JIT'd (cold Kotlin compile
 *      is ~6.6 s on the A56; warm is ~350–550 ms — the whole point).
 *   2. The resolved compile classpath (android.jar [+ any dependency jars]).
 *   3. The kotlin-stdlib DEX, dexed once and reused as classes2.dex — so the warm
 *      loop only re-dexes the changed app class, not the whole stdlib. This is the
 *      incremental-dex model real hot-reload systems use.
 *   4. The linked resources apk + generated R.class — rebuilt only when res/ changes.
 *
 * Per-save (the warm path):
 *   kotlinc(changed .kt) → app .class → warm in-process d8(app classes) → classes.dex
 *   → package [cached resources.apk + classes.dex(app) + classes2.dex(stdlib cached)]
 *   → deploy to the shell (adb push; on-device this is a local file write).
 *
 * Fresh K2JVMCompiler per request (the instance isn't reentrant) but the JVM stays
 * warm — exactly the Kotlin-daemon model, minus Gradle overhead.
 *
 * Args: <spikeRoot> <payloadKotlinDir> <android.jar> <kotlin-stdlib.jar> <d8.jar>
 * System props supply aapt2 (-Daapt2=...) and the deploy toggle (-Ddeploy=false for dry runs).
 */
public class KotlinCompileService {

    static Path spikeRoot, payloadDir, srcDir, resDir, manifestPath, workDir, classesDir, appDexDir,
            libDexDir, genDir, resourcesApk, outApk;
    static String androidJar, stdlibJar, d8Jar, aapt2;
    static boolean deploy = true;
    static boolean tier1Enabled = "true".equals(System.getProperty("tier1"));
    static volatile long lastDeployAt = 0;
    static volatile int gen = 0;

    // ---- on-device operation (ADFA-4128 on-device loop) ----
    /** Debounce window: wait this long after the first change so a burst of editor
     *  writes (a "save all") classifies as ONE build. Bryan asked for 0.1 s. */
    static final int DEBOUNCE_MS = 100;
    /** On-device deploy: instead of `adb push`, publish the apk over HTTP (the shell
     *  long-polls GET /payload) AND/OR atomic-write it to a local drop path (-Ddrop=).
     *  serveMode is implied on-device; the Mac path (adb push) is unchanged. */
    static String dropPath = System.getProperty("drop");
    static boolean serveMode = "true".equals(System.getProperty("serve")) || dropPath != null;
    /** When false (-Dwatch=false), the daemon does NOT run its own file watcher —
     *  CoGo owns the watching + 0.1 s debounce and triggers builds via POST /build. */
    static boolean watchEnabled = !"false".equals(System.getProperty("watch"));
    /** Serialize builds so a POST /build and a watcher event can't overlap. */
    static final Object buildLock = new Object();
    // Serve channel: the shell pulls the latest payload apk from GET /payload?have=N.
    static volatile int serveGen = 0;
    static volatile byte[] serveApk = null;
    static volatile String serveDigest = null;   // SHA-256 hex of serveApk (Step-4 handshake)
    static volatile long lastBuildMs = 0;         // compile+dex+pack of the last build
    static volatile long serveBuildMs = 0;        // lastBuildMs snapshot for the served payload
    // True from the moment a /build is received (files already written on-device) until the
    // compile+dex+deploy completes. The shell polls /status for this so it can show a
    // distinct "edits done — compiling on device" phase instead of implying Claude is still
    // writing during the on-device build.
    static volatile boolean building = false;
    static final Object serveLock = new Object();
    /** Payload's own class digests at the last deploy — the Tier-1 gate baseline. */
    static java.util.Map<String, String> lastAppDigests = null;

    // ---- incremental compile (Build Tools API) ----
    /** ON by default; -Dinc=false falls back to the whole-app K2JVMCompiler path. */
    static boolean incEnabled = !"false".equals(System.getProperty("inc"));
    /** R.class output — a SEPARATE, stable classpath entry (the IC engine snapshots it;
     *  it must not be the kotlinc output dir, which the engine manages itself). */
    static Path rDir;
    static IncrementalCompiler incCompiler;
    static volatile boolean incSeeded = false;  // true after the first (cold) incremental compile
    /** .kt files changed since the last compile, collected by the watch loop (single consumer). */
    static final java.util.Set<Path> changedSrc = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        spikeRoot = Path.of(args[0]);
        payloadDir = Path.of(args[1]);
        androidJar = args[2];
        stdlibJar = args[3];
        d8Jar = args[4];
        aapt2 = System.getProperty("aapt2", "aapt2");
        deploy = !"false".equals(System.getProperty("deploy"));

        // src/res/manifest default to the mini-stubby payload layout, but are overridable
        // (-Dsrc= / -Dres= / -Dmanifest=) so CoGo can point them at a real project's
        // app/src/main/{java,res,AndroidManifest.xml} without changing the args contract.
        srcDir = Path.of(System.getProperty("src", payloadDir.resolve("src").toString()));
        resDir = Path.of(System.getProperty("res", payloadDir.resolve("res").toString()));
        manifestPath = Path.of(System.getProperty("manifest",
                payloadDir.resolve("AndroidManifest.xml").toString()));
        workDir = Path.of(System.getProperty("work", spikeRoot.resolve("build/compile-service").toString()));
        classesDir = workDir.resolve("classes");
        appDexDir = workDir.resolve("appdex");
        libDexDir = workDir.resolve("libdex");
        genDir = workDir.resolve("gen");
        resourcesApk = workDir.resolve("resources.apk");
        outApk = workDir.resolve("payload-kotlin.apk");
        rDir = workDir.resolve("rclasses");
        Files.createDirectories(classesDir);
        Files.createDirectories(rDir);
        // Resume the serve counter across daemon restarts so a shell that already pulled
        // gen N keeps advancing (a fresh counter would sit below the shell's cursor -> stuck).
        try { serveGen = Integer.parseInt(Files.readString(workDir.resolve("servegen")).trim()); }
        catch (Exception ignore) { /* first run */ }

        log("service starting (deploy=" + deploy + ", inc=" + incEnabled
                + ", serve=" + serveMode + ", watch=" + watchEnabled
                + (dropPath != null ? ", drop=" + dropPath : "") + ")");

        // ---- one-time warm setup ----
        long s0 = now();
        linkResources();                 // aapt2 → resources.apk + R.java
        compileRClass();                 // javac R.java → R.class (kotlin classpath needs it)
        dexStdlibOnce();                 // d8 kotlin-stdlib → cached classes2.dex
        if (incEnabled) {
            // Snapshot the fixed classpath (android.jar + R + stdlib) ONCE; the IC engine
            // then tracks source changes across saves.
            incCompiler = new IncrementalCompiler(
                    List.of(androidJar, rDir.toString(), stdlibJar), workDir.resolve("inc"));
            log("incremental compile ENABLED (Kotlin Build Tools API)");
        }
        log("one-time setup " + (now() - s0) + " ms (aapt2 + R + stdlib-dex"
                + (incEnabled ? " + cp-snapshot" : "") + ")");

        // Warm-up compile: pays the ~6.6 s cold Kotlin cost ONCE so real edits are warm.
        long w0 = now();
        buildAndDeploy(true, false);
        log("warm-up (cold compiler) " + (now() - w0) + " ms — subsequent saves are warm");

        startHttp();                     // /reloaded timing, /payload pull channel, /build trigger
        if (watchEnabled) {
            watchLoop();                 // block forever, rebuild on save
        } else {
            log("file watcher disabled — CoGo drives builds via POST /build");
            new java.util.concurrent.CountDownLatch(1).await();   // park; HTTP threads keep serving
        }
    }

    // ---------- the warm per-save path ----------

    static void buildAndDeploy(boolean warmup, boolean resChanged) throws Exception {
        long t0 = now();
        // 1. Kotlin compile (warm JVM). classpath = android.jar + R.class dir + stdlib.
        //    R lives in rDir (a STABLE classpath entry the IC engine snapshots), NOT in the
        //    kotlinc output dir (classesDir), which the IC engine manages itself.
        List<Path> kt = new ArrayList<>();
        collect(srcDir, ".kt", kt);
        String cp = androidJar + File.pathSeparator + rDir + File.pathSeparator + stdlibJar;
        long k0 = now();
        boolean incremental = false;
        if (incEnabled) {
            // A resource change regenerated R.java → recompile R and re-seed incremental
            // state so the new R ABI is on the snapshotted classpath.
            if (resChanged) { compileRClass(); reseedIncremental(); }
            List<File> allSrc = new ArrayList<>();
            for (Path p : kt) allSrc.add(p.toFile());
            List<File> changed;
            if (warmup || !incSeeded || resChanged) {
                changed = allSrc;                          // cold / re-seed → recompile all
            } else {
                java.util.Set<Path> drained = new java.util.HashSet<>(changedSrc);
                changedSrc.clear();
                drained.retainAll(new java.util.HashSet<>(kt)); // drop deletes/stale
                changed = new ArrayList<>();
                for (Path p : drained) changed.add(p.toFile());
                if (changed.isEmpty()) changed = allSrc;   // unknown change → full (safe)
                else incremental = true;
            }
            boolean ok = incCompiler.compile(allSrc, changed, cp, classesDir,
                    List.of("-Xlambdas=class", "-Xsam-conversions=class"));
            lastDiag = incCompiler.diagnostics();
            if (ok) incSeeded = true;
            else { log("kotlinc FAILED (incremental):\n" + lastDiag.trim()); return; }
        } else {
            deleteClassFilesUnder(classesDir);             // whole-app path clears app classes
            ExitCode code = kotlinCompile(kt, cp, classesDir);
            if (code != ExitCode.OK) { log("kotlinc FAILED (" + code + "):\n" + lastDiag.trim()); return; }
        }
        long kMs = now() - k0;
        lastAppDigests = appClassDigests(); // Tier-1 gate baseline stays in sync with the deploy

        // 2. warm in-process d8 of the app classes + R → classes.dex.
        long d0 = now();
        runD8Multi(List.of(classesDir, rDir), appDexDir);
        long dMs = now() - d0;

        // 3. package: resources.apk + app classes.dex + cached stdlib classes2.dex.
        long p0 = now();
        Files.copy(resourcesApk, outApk, StandardCopyOption.REPLACE_EXISTING);
        zipInto(outApk, appDexDir.resolve("classes.dex"), "classes.dex");
        zipInto(outApk, libDexDir.resolve("classes.dex"), "classes2.dex");
        long pMs = now() - p0;
        lastBuildMs = now() - t0;   // compile+dex+pack — set BEFORE deploy so publishServe snapshots it

        // 4. deploy.
        long dep0 = now();
        if (deploy) deploy(outApk);
        long depMs = now() - dep0;

        gen++;
        lastDeployAt = now();
        long total = now() - t0;
        String mode = warmup ? "warmup" : (incremental ? "TIER2 code (INCREMENTAL compile+reload)"
                : "TIER2 code (full compile+reload)");
        log(mode + " total=" + total + "ms (kotlinc=" + kMs + " d8=" + dMs
                + " pack=" + pMs + " deploy=" + (deploy ? depMs + "ms" : "skipped") + ")");
    }

    /**
     * TIER 0 — resource/UI edit: NO compiler. Re-link resources (aapt2), reuse the
     * cached app + stdlib dex, repackage, deploy. The overwhelming-majority case
     * during UI work (layout/string/color/dimen tweaks) and the cheapest path.
     */
    static void resourceOnlyChange() throws Exception {
        long t0 = now();
        long a0 = now();
        linkResources();                 // aapt2 compile+link only
        long aMs = now() - a0;
        long p0 = now();
        Files.copy(resourcesApk, outApk, StandardCopyOption.REPLACE_EXISTING);
        zipInto(outApk, appDexDir.resolve("classes.dex"), "classes.dex");   // cached
        zipInto(outApk, libDexDir.resolve("classes.dex"), "classes2.dex");  // cached
        long pMs = now() - p0;
        lastBuildMs = now() - t0;   // aapt2+pack — set BEFORE deploy so publishServe snapshots it
        long dep0 = now();
        if (deploy) deploy(outApk);
        long depMs = now() - dep0;
        gen++;
        lastDeployAt = now();
        log("TIER0 resource (NO compiler) total=" + (now() - t0) + "ms (aapt2=" + aMs
                + " pack=" + pMs + " deploy=" + (deploy ? depMs + "ms" : "skipped") + ")");
    }

    /**
     * Code-edit dispatch. Attempts TIER 1 (compile the changed class → dex →
     * ART class redefinition in the running shell, no reload, state preserved);
     * on unavailability or a structural change (add/remove method/field/class,
     * signature change) that ART can't redefine, falls through to TIER 2.
     */
    static void codeChange(boolean forceTier2) throws Exception {
        if (tier1Enabled && !forceTier2 && tryTier1Redefine()) return;
        buildAndDeploy(false, forceTier2 /* resChanged */); // TIER 2
    }

    /** Dex a set of class-dirs together (app classesDir + R rDir) into one classes.dex. */
    static void runD8Multi(List<Path> roots, Path outDir) throws Exception {
        List<Path> classFiles = new ArrayList<>();
        for (Path r : roots) collect(r, ".class", classFiles);
        d8(classFiles, outDir);
    }

    /** Rebuild the incremental compiler from a clean state (used when R changed, so the new
     *  R ABI is re-snapshotted). Resource changes are rare vs code edits, so the cold re-seed
     *  on the next compile is an acceptable cost. */
    static void reseedIncremental() throws Exception {
        deleteRecursively(workDir.resolve("inc"));
        incCompiler = new IncrementalCompiler(
                List.of(androidJar, rDir.toString(), stdlibJar), workDir.resolve("inc"));
        incSeeded = false;
    }

    static boolean tryTier1Redefine() throws Exception {
        long t0 = now();
        List<Path> kt = new ArrayList<>();
        collect(srcDir, ".kt", kt);
        String cp = androidJar + File.pathSeparator + rDir + File.pathSeparator + stdlibJar;
        deleteClassFilesUnder(classesDir);
        long k0 = now();
        ExitCode code = kotlinCompile(kt, cp, classesDir);
        long kMs = now() - k0;
        if (code != ExitCode.OK) {
            log("kotlinc FAILED (" + code + "):\n" + lastDiag.trim());
            return true; // handled (compile error); don't fall to Tier 2
        }

        // GATE (#4): Tier 1 is only valid when the ONLY change is Main.class's body.
        // A new lambda/SAM sibling, an edited helper class, or add/remove of any
        // class → Tier 2, else the redefine is a no-op (invisible edit) or the
        // process references a class in no loaded dex (NoClassDefFoundError crash).
        java.util.Map<String, String> digests = appClassDigests();
        if (!onlyMainChanged(lastAppDigests, digests)) {
            log("TIER1 skipped (not a Main-body-only change) — TIER2");
            return false;
        }

        // Dex ONLY Main.class → a true single-class dex (ART wants the dex's class
        // set to match the redefine target). Lambdas/SAMs are separate named
        // classes (-Xsam-conversions=class) and unchanged, so they aren't dexed.
        List<Path> mainClasses = new ArrayList<>();
        try (var walk = Files.walk(classesDir)) {
            walk.filter(p -> p.getFileName().toString().equals("Main.class"))
                .forEach(mainClasses::add);
        }
        Path redefineDexDir = workDir.resolve("redefinedex");
        d8(mainClasses, redefineDexDir);
        Boolean ok = pushRedefine(redefineDexDir.resolve("classes.dex"));
        if (ok == null) { log("TIER1 agent unavailable — falling back to TIER2"); return false; }
        if (!ok) { log("TIER1 rejected (structural change) — falling back to TIER2"); return false; }

        // CONVERGE (#1): the redefine changed the RUNNING process, but the deployed
        // artifacts still hold pre-edit code. Refresh the Mac-side caches (full dex
        // + repackaged apk) so a later TIER 0 (which reuses appDexDir) doesn't ship
        // stale code and silently revert the hot-swap. (On-device payload.apk is
        // NOT re-pushed — that would trigger a redundant reload; a shell RESTART
        // therefore reloads the last full deploy, a documented spike limitation.)
        runD8Multi(List.of(classesDir, rDir), appDexDir);
        Files.copy(resourcesApk, outApk, StandardCopyOption.REPLACE_EXISTING);
        zipInto(outApk, appDexDir.resolve("classes.dex"), "classes.dex");
        zipInto(outApk, libDexDir.resolve("classes.dex"), "classes2.dex");
        lastAppDigests = digests;

        gen++;
        lastDeployAt = now();
        log("TIER1 hot-swap (redefine, NO reload) total=" + (now() - t0)
                + "ms (kotlinc=" + kMs + ")");
        return true;
    }

    /** Last kotlinc diagnostics — captured so a COMPILATION_ERROR reports file/line,
     *  not just an opaque exit code (this is the main feedback channel in the loop). */
    static volatile String lastDiag = "";

    static ExitCode kotlinCompile(List<Path> sources, String classpath, Path out) {
        K2JVMCompiler compiler = new K2JVMCompiler(); // fresh per request; JVM stays warm
        List<String> a = new ArrayList<>();
        for (Path p : sources) a.add(p.toString());
        a.add("-classpath"); a.add(classpath);
        a.add("-d"); a.add(out.toString());
        a.add("-jvm-target"); a.add("17");
        // Emit lambdas AND SAM conversions (setOnClickListener {…}) as separate
        // NAMED .class files instead of invokedynamic. Without -Xsam-conversions,
        // the OnClickListener SAM stays indy and d8 desugars it into
        // Main$$ExternalSyntheticLambda0 INSIDE Main's dex — so d8 of just
        // Main.class still yields a 2-class dex, and ART's RedefineClasses rejects
        // a dex whose class set doesn't match the definitions (ILLEGAL_ARGUMENT).
        // With both flags, d8 of Main.class alone is a TRUE single-class dex.
        a.add("-Xlambdas=class");
        a.add("-Xsam-conversions=class");
        a.add("-no-stdlib"); a.add("-no-reflect"); a.add("-nowarn");
        ByteArrayOutputStream diag = new ByteArrayOutputStream();
        ExitCode code = compiler.exec(new PrintStream(diag), a.toArray(new String[0]));
        lastDiag = diag.toString(StandardCharsets.UTF_8);
        return code;
    }

    // ---------- Tier-1 class-set gate (finding #4) ----------

    /** SHA-256 of the payload's OWN compiled classes (Main.class, Main$render$1,
     *  …) keyed by relative name; R* excluded (stable, javac'd once at startup). */
    static java.util.Map<String, String> appClassDigests() throws Exception {
        java.util.Map<String, String> m = new java.util.TreeMap<>();
        List<Path> cls = new ArrayList<>();
        collect(classesDir, ".class", cls);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        for (Path p : cls) {
            String rel = classesDir.relativize(p).toString();
            if (rel.matches(".*\\bR(\\$[^/]*)?\\.class$")) continue; // skip generated R
            md.reset();
            m.put(rel, java.util.HexFormat.of().formatHex(md.digest(Files.readAllBytes(p))));
        }
        return m;
    }

    /** Tier 1 is safe iff the ONLY thing that changed vs the last build is the body
     *  of app/payload/Main.class: no class added/removed, and no OTHER class's bytes
     *  changed. Anything else (new lambda sibling, edited helper class, signature
     *  change) must take Tier 2 — otherwise the redefine is a no-op or the process
     *  references a class that exists in no loaded dex (NoClassDefFoundError). */
    static boolean onlyMainChanged(java.util.Map<String, String> before,
                                   java.util.Map<String, String> after) {
        if (before == null) return false;
        if (!before.keySet().equals(after.keySet())) return false; // add/remove
        String main = "app/payload/Main.class".replace('/', File.separatorChar);
        for (java.util.Map.Entry<String, String> e : after.entrySet()) {
            boolean same = e.getValue().equals(before.get(e.getKey()));
            if (!same && !e.getKey().equals(main)) return false;    // some OTHER class changed
        }
        return !after.getOrDefault(main, "").equals(before.getOrDefault(main, "")); // Main did change
    }

    // ---------- one-time setup ----------

    static void linkResources() throws Exception {
        Files.createDirectories(genDir);
        Path resZip = workDir.resolve("res.zip");
        run(aapt2, "compile", "--dir", resDir.toString(), "-o", resZip.toString());
        run(aapt2, "link", "--manifest", manifestPath.toString(),
                "-I", androidJar, "--package-id", "0x80", "--allow-reserved-package-id",
                "--min-sdk-version", "30", "--target-sdk-version", "34",
                "--java", genDir.toString(), "-o", resourcesApk.toString(), resZip.toString());
    }

    static void compileRClass() throws Exception {
        List<Path> rJava = new ArrayList<>();
        collect(genDir, ".java", rJava);
        List<String> a = new ArrayList<>(List.of("-classpath", androidJar, "-d", rDir.toString()));
        for (Path p : rJava) a.add(p.toString());
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (jc.run(null, null, null, a.toArray(new String[0])) != 0)
            throw new Exception("javac R.java failed");
    }

    static void dexStdlibOnce() throws Exception {
        runD8FromJars(List.of(Path.of(stdlibJar)), libDexDir);
    }

    // ---------- warm in-process d8 (reflection on d8.jar) ----------

    static void runD8(Path classesRoot, Path outDir) throws Exception {
        List<Path> classFiles = new ArrayList<>();
        collect(classesRoot, ".class", classFiles);
        d8(classFiles, outDir);
    }
    static void runD8FromJars(List<Path> jars, Path outDir) throws Exception { d8(jars, outDir); }

    static void d8(List<Path> inputs, Path outDir) throws Exception {
        deleteRecursively(outDir);
        Files.createDirectories(outDir);
        Class<?> d8Cls = Class.forName("com.android.tools.r8.D8");
        Class<?> cmdCls = Class.forName("com.android.tools.r8.D8Command");
        Class<?> outputModeCls = Class.forName("com.android.tools.r8.OutputMode");
        Object builder = cmdCls.getMethod("builder").invoke(null);
        Class<?> b = builder.getClass();
        b.getMethod("addProgramFiles", Collection.class).invoke(builder, inputs);
        b.getMethod("addLibraryFiles", Collection.class).invoke(builder, List.of(Path.of(androidJar)));
        b.getMethod("setMinApiLevel", int.class).invoke(builder, 30);
        Object dexIndexed = outputModeCls.getField("DexIndexed").get(null);
        b.getMethod("setOutput", Path.class, outputModeCls).invoke(builder, outDir, dexIndexed);
        Object command = b.getMethod("build").invoke(builder);
        Method run = d8Cls.getMethod("run", cmdCls);
        run.invoke(null, command);
    }

    // ---------- watch + http + shell-out ----------

    static void watchLoop() throws Exception {
        WatchService ws = FileSystems.getDefault().newWatchService();
        // WatchService is NOT recursive — register src AND res (and subdirs).
        registerRecursive(ws, srcDir);
        registerRecursive(ws, resDir);
        log("watching src + res (recursive) — edit a .kt (→ code path) or res (→ TIER0)");
        while (true) {
            WatchKey key = ws.take();
            Thread.sleep(DEBOUNCE_MS); // debounce — coalesce a burst of writes into one build
            // Drain all queued keys so a burst of writes classifies as one save.
            boolean srcChanged = false, resChanged = false;
            List<WatchKey> keys = new ArrayList<>();
            keys.add(key);
            WatchKey k2;
            while ((k2 = ws.poll()) != null) keys.add(k2);
            for (WatchKey kk : keys) {
                Path dir = (Path) kk.watchable();
                boolean inSrc = dir.startsWith(srcDir), inRes = dir.startsWith(resDir);
                for (WatchEvent<?> ev : kk.pollEvents()) {
                    Object ctx = ev.context();
                    Path name = (ctx instanceof Path) ? (Path) ctx : null;
                    if (inSrc) {
                        srcChanged = true;
                        // Record the specific .kt so the incremental compiler recompiles just it.
                        if (name != null && name.toString().endsWith(".kt")) changedSrc.add(dir.resolve(name));
                    }
                    if (inRes) resChanged = true;
                }
                kk.reset();
            }
            // TIER DISPATCH: a code change takes the code path; a resource-only
            // change takes Tier 0 (no compiler). A MIXED save (both changed, a
            // common IDE save shape) must relink resources AND force Tier 2 —
            // Tier 1 can't carry a resource change, and using the cached
            // resources.apk would ship stale resources (finding #3).
            try {
                synchronized (buildLock) {   // don't race a POST /build from CoGo
                    if (srcChanged) {
                        if (resChanged) linkResources();
                        codeChange(resChanged /* forceTier2 */);
                    } else if (resChanged) {
                        resourceOnlyChange();
                    }
                }
            } catch (Throwable t) { log("build error (service alive): " + t); }
        }
    }

    static void registerRecursive(WatchService ws, Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).forEach(d -> {
                try {
                    d.register(ws, StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE);
                } catch (Exception e) { log("watch register failed: " + d); }
            });
        }
    }

    static void startHttp() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 8378), 0);
        http.createContext("/reloaded", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            long e2e = now() - lastDeployAt;
            String s = new String(body, StandardCharsets.UTF_8);
            log("save→RENDERED end-to-end " + e2e + "ms  (device: " + s.trim() + ")");
            ex.sendResponseHeaders(200, 0); ex.close();
        });
        // On-device deploy channel: the shell long-polls this for the latest payload apk.
        // Returns 200 + apk bytes (+ X-Gen header) once serveGen advances past ?have=N,
        // else 204 after a ~25 s hold. No adb, no shared storage, no run-as.
        http.createContext("/payload", ex -> {
            try {
                int have = intParam(ex.getRequestURI().getRawQuery(), "have", 0);
                byte[] body; int g; String digest; long buildMs;
                synchronized (serveLock) {
                    long deadline = System.currentTimeMillis() + 25_000;
                    while (serveGen <= have) {
                        long wait = deadline - System.currentTimeMillis();
                        if (wait <= 0) break;
                        serveLock.wait(wait);
                    }
                    g = serveGen; body = serveApk; digest = serveDigest; buildMs = serveBuildMs;
                }
                if (g > have && body != null) {
                    ex.getResponseHeaders().add("X-Gen", Integer.toString(g));
                    if (digest != null) ex.getResponseHeaders().add("X-Digest", digest);
                    ex.getResponseHeaders().add("X-Build-Ms", Long.toString(buildMs));
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                } else {
                    ex.sendResponseHeaders(204, -1);
                }
            } catch (Exception e) {
                try { ex.sendResponseHeaders(500, -1); } catch (Exception ignore) {}
            } finally { ex.close(); }
        });
        // CoGo build trigger: CoGo owns the file watching + 0.1 s debounce and POSTs here
        // after flushing the editor. ?kind=code (compile+dex) | res (aapt2 relink only).
        http.createContext("/build", ex -> {
            String result;
            building = true;   // files are already on-device; the on-device compile starts now
            try {
                String kind = intParamRaw(ex.getRequestURI().getRawQuery(), "kind", "code");
                // Optional body: newline-separated absolute paths of the changed .kt files,
                // so the incremental compiler recompiles JUST those (not the whole app).
                // CoGo's watcher knows exactly what changed and sends them here — this is
                // what keeps a CoGo-triggered build incremental with watch=false.
                byte[] body = ex.getRequestBody().readAllBytes();
                if (body.length > 0) {
                    for (String line : new String(body, StandardCharsets.UTF_8).split("\\R")) {
                        String p = line.trim();
                        if (p.endsWith(".kt")) changedSrc.add(Path.of(p));
                    }
                }
                synchronized (buildLock) {
                    if ("res".equals(kind)) resourceOnlyChange();
                    else codeChange(false);
                }
                result = "ok gen=" + gen;
            } catch (Throwable t) {
                result = "error " + t;
                log("POST /build error: " + t);
            } finally {
                building = false;
            }
            byte[] r = result.getBytes(StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(200, r.length); ex.getResponseBody().write(r); }
            catch (Exception ignore) {}
            finally { ex.close(); }
        });
        // Lightweight phase probe: the shell polls this to show "edits done — compiling"
        // while a build is in flight (files written, compile running), distinct from the
        // long "Claude is writing" wait. Text: "building=<0|1> gen=<serveGen>".
        http.createContext("/status", ex -> {
            byte[] r = ("building=" + (building ? 1 : 0) + " gen=" + serveGen)
                    .getBytes(StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(200, r.length); ex.getResponseBody().write(r); }
            catch (Exception ignore) {}
            finally { ex.close(); }
        });
        // Long-polls hold a thread each — a single-thread executor would starve /reloaded
        // and /build. Use a cached pool.
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
        log("listening on 127.0.0.1:8378 (/reloaded, /payload"
                + (serveMode ? " [serve ON]" : "") + ", /build)");
    }

    /** Parse an int query param from a raw query string (e.g. "have=3&x=1"). */
    static int intParam(String rawQuery, String key, int dflt) {
        String v = intParamRaw(rawQuery, key, null);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return dflt; }
    }
    static String intParamRaw(String rawQuery, String key, String dflt) {
        if (rawQuery == null) return dflt;
        for (String kv : rawQuery.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
        }
        return dflt;
    }

    static void deploy(Path apk) throws Exception {
        if (serveMode) {
            // On-device: no adb, no run-as, no shared-storage FUSE inotify. Publish the
            // apk bytes so the shell's long-poll GET /payload picks them up (the shell
            // writes them into its OWN private watched dir → existing reload plumbing).
            publishServe(apk);
            if (dropPath != null) writeDrop(apk);   // optional file drop for inspection/tests
            return;
        }
        // Mac harness path (unchanged): adb push + run-as atomic rename into the shell.
        run("sh", spikeRoot.resolve("tools/deploy_payload.sh").toString(), apk.toString());
    }

    /** Publish the latest payload apk for the shell's GET /payload long-poll, with its
     *  SHA-256 for the Step-4 digest handshake (the shell loads code only if the bytes
     *  it received hash to this — mitigates D7 "shell runs whatever lands in its dir"). */
    static void publishServe(Path apk) throws Exception {
        byte[] bytes = Files.readAllBytes(apk);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        String digest = java.util.HexFormat.of().formatHex(md.digest(bytes));
        synchronized (serveLock) {
            serveApk = bytes;
            serveDigest = digest;
            serveBuildMs = lastBuildMs;   // honest banner: the build time behind this payload
            serveGen++;
            serveLock.notifyAll();
        }
        try { Files.writeString(workDir.resolve("servegen"), Integer.toString(serveGen)); }
        catch (Exception ignore) { /* best-effort persistence */ }
    }

    /** Atomic local file drop (-Ddrop=). Same temp-then-rename trick deploy_payload.sh
     *  uses, so a watcher on the drop sees one complete apk, never a partial write. */
    static void writeDrop(Path apk) throws Exception {
        Path dst = Path.of(dropPath);
        if (dst.getParent() != null) Files.createDirectories(dst.getParent());
        Path tmp = dst.resolveSibling(".incoming.tmp");
        Files.copy(apk, tmp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Hand a redefine dex to the on-device JVMTI agent and read its verdict.
     * Protocol (see hotswap-agent/): push the dex into the shell's private
     * files/hotswap/redefine.dex, then a trigger file; the agent redefines the
     * loaded class(es) and writes files/hotswap/result (0=ok, 1=structural).
     * Returns null if the agent isn't installed/attached (→ Tier 2 fallback).
     */
    static Boolean pushRedefine(Path dex) throws Exception {
        String pkg = "com.adfa.ministubby.host";
        // Separate SINGLE run-as commands (no sh -c / compound / redirection —
        // those mangle through Java→sh→adb→run-as→sh). run-as cp reads
        // /data/local/tmp and writes the app's private files (same pattern
        // deploy_payload.sh uses). Trigger is a pushed file, not a shell redirect.
        // NONCE (#7): the agent echoes it in the result so a STALE result (failed
        // delete / adb hiccup) can't be mistaken for this request's outcome.
        String nonce = "n" + (++redefineNonce) + "_" + now();
        Path trg = Files.createTempFile("redef", ".trigger");
        try {
            Files.writeString(trg, "app.payload.Main " + nonce);
            // Fail fast to Tier 2 if any staging command errors (exit-code checked).
            if (!shOk("adb push " + dex + " /data/local/tmp/redefine.dex")) return null;
            if (!shOk("adb push " + trg + " /data/local/tmp/redefine.trigger")) return null;
            shOk("adb shell run-as " + pkg + " rm -f files/hotswap/result");
            if (!shOk("adb shell run-as " + pkg
                    + " cp /data/local/tmp/redefine.dex files/hotswap/redefine.dex")) return null;
            // trigger LAST so the dex is fully staged when the agent wakes
            if (!shOk("adb shell run-as " + pkg
                    + " cp /data/local/tmp/redefine.trigger files/hotswap/trigger")) return null;
            for (int i = 0; i < 60; i++) {
                String r = sh("adb shell run-as " + pkg + " cat files/hotswap/result").trim();
                if (r.startsWith(nonce + " ")) {     // ONLY accept OUR nonce's result
                    return r.endsWith("0") ? Boolean.TRUE : Boolean.FALSE;
                }
                Thread.sleep(15);
            }
            return null; // no agent attached / timed out → fallback
        } finally {
            Files.deleteIfExists(trg); // #15: don't leak a temp file per attempt
        }
    }

    static long redefineNonce = 0;

    static String sh(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    /** Like sh() but returns whether the command exited 0 (finding #7). */
    static boolean shOk(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor() == 0;
    }

    // ---------- small helpers ----------

    static void zipInto(Path apk, Path file, String entryName) throws Exception {
        // Add/replace a single entry, in pure Java — no `zip` CLI (so the daemon needs no
        // Termux binary on-device). The apk is tiny (resources.apk + 2 dex), so a full
        // rewrite is sub-millisecond. Existing entries are copied byte-for-byte with their
        // ORIGINAL method preserved — critical for aapt2's STORED resources.arsc, which
        // AssetManager/ResourcesLoader mmap and must stay uncompressed.
        Path tmp = apk.resolveSibling(apk.getFileName() + ".ztmp");
        try (ZipFile zf = new ZipFile(apk.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().equals(entryName)) continue;   // replaced below
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setMethod(e.getMethod());
                ne.setTime(e.getTime());
                if (e.getMethod() == ZipEntry.STORED) {
                    ne.setSize(e.getSize());
                    ne.setCompressedSize(e.getSize());
                    ne.setCrc(e.getCrc());
                }
                zos.putNextEntry(ne);
                try (InputStream is = zf.getInputStream(e)) { is.transferTo(zos); }
                zos.closeEntry();
            }
            ZipEntry ne = new ZipEntry(entryName);   // add the new/updated entry (deflated)
            zos.putNextEntry(ne);
            Files.copy(file, zos);
            zos.closeEntry();
        }
        Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new Exception("cmd failed (" + rc + "): " + String.join(" ", cmd)
                + "\n" + out);
    }

    static void collect(Path root, String ext, List<Path> into) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(ext)).forEach(into::add);
        }
    }
    static void deleteClassFilesUnder(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            // Keep ONLY the generated R (R.class / R$id.class …), which is javac'd
            // once at startup. Matching "starts with R" also kept user classes like
            // RowView/Repo, which then lingered stale across renames (finding #11).
            walk.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".class") && !n.equals("R.class") && !n.matches("R\\$.*\\.class");
                    })
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }
    static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    static long now() { return System.nanoTime() / 1_000_000; }
    static void log(String m) { System.out.println("KCS " + m); }
}
