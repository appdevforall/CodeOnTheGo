import com.sun.net.httpserver.HttpServer;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    static Path spikeRoot, payloadDir, srcDir, resDir, workDir, classesDir, appDexDir,
            libDexDir, genDir, resourcesApk, outApk;
    static String androidJar, stdlibJar, d8Jar, aapt2;
    static boolean deploy = true;
    static boolean tier1Enabled = "true".equals(System.getProperty("tier1"));
    static volatile long lastDeployAt = 0;
    static volatile int gen = 0;

    public static void main(String[] args) throws Exception {
        spikeRoot = Path.of(args[0]);
        payloadDir = Path.of(args[1]);
        androidJar = args[2];
        stdlibJar = args[3];
        d8Jar = args[4];
        aapt2 = System.getProperty("aapt2", "aapt2");
        deploy = !"false".equals(System.getProperty("deploy"));

        srcDir = payloadDir.resolve("src");
        resDir = payloadDir.resolve("res");
        workDir = spikeRoot.resolve("build/compile-service");
        classesDir = workDir.resolve("classes");
        appDexDir = workDir.resolve("appdex");
        libDexDir = workDir.resolve("libdex");
        genDir = workDir.resolve("gen");
        resourcesApk = workDir.resolve("resources.apk");
        outApk = workDir.resolve("payload-kotlin.apk");
        Files.createDirectories(classesDir);

        log("service starting (deploy=" + deploy + ")");

        // ---- one-time warm setup ----
        long s0 = now();
        linkResources();                 // aapt2 → resources.apk + R.java
        compileRClass();                 // javac R.java → R.class (kotlin classpath needs it)
        dexStdlibOnce();                 // d8 kotlin-stdlib → cached classes2.dex
        log("one-time setup " + (now() - s0) + " ms (aapt2 + R + stdlib-dex)");

        // Warm-up compile: pays the ~6.6 s cold Kotlin cost ONCE so real edits are warm.
        long w0 = now();
        buildAndDeploy(true);
        log("warm-up (cold compiler) " + (now() - w0) + " ms — subsequent saves are warm");

        startHttp();                     // receives /reloaded from the shell for end-to-end timing
        watchLoop();                     // block forever, rebuild on save
    }

    // ---------- the warm per-save path ----------

    static void buildAndDeploy(boolean warmup) throws Exception {
        long t0 = now();
        // 1. Kotlin compile (warm JVM, fresh compiler). classpath = android.jar +
        //    R.class dir + stdlib (compile-time refs); -no-stdlib so stdlib isn't
        //    re-emitted (it's already dexed as classes2.dex).
        List<Path> kt = new ArrayList<>();
        collect(srcDir, ".kt", kt);
        String cp = androidJar + File.pathSeparator + classesDir + File.pathSeparator + stdlibJar;
        deleteClassFilesUnder(classesDir); // keep R.class (in genClasses), clear app classes
        long k0 = now();
        ExitCode code = kotlinCompile(kt, cp, classesDir);
        long kMs = now() - k0;
        if (code != ExitCode.OK) {
            log("kotlinc FAILED (" + code + ") — skipping d8/deploy, service alive");
            return;
        }

        // 2. warm in-process d8 of ONLY the app classes → classes.dex.
        long d0 = now();
        runD8(classesDir, appDexDir);
        long dMs = now() - d0;

        // 3. package: resources.apk + app classes.dex + cached stdlib classes2.dex.
        long p0 = now();
        Files.copy(resourcesApk, outApk, StandardCopyOption.REPLACE_EXISTING);
        zipInto(outApk, appDexDir.resolve("classes.dex"), "classes.dex");
        zipInto(outApk, libDexDir.resolve("classes.dex"), "classes2.dex");
        long pMs = now() - p0;

        // 4. deploy.
        long dep0 = now();
        if (deploy) deploy(outApk);
        long depMs = now() - dep0;

        gen++;
        lastDeployAt = now();
        long total = now() - t0;
        log((warmup ? "warmup" : "TIER2 code (full compile+reload)")
                + " total=" + total + "ms (kotlinc=" + kMs + " d8=" + dMs
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
    static void codeChange() throws Exception {
        if (tier1Enabled && tryTier1Redefine()) return;
        buildAndDeploy(false); // TIER 2
    }

    static boolean tryTier1Redefine() throws Exception {
        long t0 = now();
        // Compile just the app class(es) and dex only those → redefine.dex.
        List<Path> kt = new ArrayList<>();
        collect(srcDir, ".kt", kt);
        String cp = androidJar + File.pathSeparator + classesDir + File.pathSeparator + stdlibJar;
        deleteClassFilesUnder(classesDir);
        long k0 = now();
        ExitCode code = kotlinCompile(kt, cp, classesDir);
        long kMs = now() - k0;
        if (code != ExitCode.OK) {
            log("kotlinc FAILED (" + code + ") — service alive");
            return true; // handled (nothing to deploy); don't fall to Tier 2
        }
        // Dex ONLY the Main class (+ its d8-desugared synthetics), not R etc. —
        // ART's RedefineClasses wants the dex to contain exactly the redefined
        // classes. redefineDexDir/classes.dex holds just Main + Main$$…
        List<Path> mainClasses = new ArrayList<>();
        try (var walk = Files.walk(classesDir)) {
            walk.filter(p -> p.getFileName().toString().equals("Main.class"))
                .forEach(mainClasses::add);   // ONLY Main.class → single-class dex
        }
        Path redefineDexDir = workDir.resolve("redefinedex");
        d8(mainClasses, redefineDexDir);
        // Hand the Main-only dex to the on-device redefine agent; it finds the
        // loaded Main (+ synthetics) and calls RedefineClasses. A "1" result means
        // structural (added/removed method/field, signature change) → fall back.
        Boolean ok = pushRedefine(redefineDexDir.resolve("classes.dex"));
        if (ok == null) { log("TIER1 agent unavailable — falling back to TIER2"); return false; }
        if (!ok) { log("TIER1 rejected (structural change) — falling back to TIER2"); return false; }
        gen++;
        lastDeployAt = now();
        log("TIER1 hot-swap (redefine, NO reload) total=" + (now() - t0)
                + "ms (kotlinc=" + kMs + ")");
        return true;
    }

    static ExitCode kotlinCompile(List<Path> sources, String classpath, Path out) {
        K2JVMCompiler compiler = new K2JVMCompiler(); // fresh per request; JVM stays warm
        List<String> a = new ArrayList<>();
        for (Path p : sources) a.add(p.toString());
        a.add("-classpath"); a.add(classpath);
        a.add("-d"); a.add(out.toString());
        a.add("-jvm-target"); a.add("17");
        // -Xlambdas=class: emit lambdas as separate named .class files (not
        // invokedynamic). This lets Tier 1 produce a single-class Main dex (d8
        // of just Main.class won't pull a desugared synthetic INTO Main's dex),
        // which ART's RedefineClasses requires.
        a.add("-Xlambdas=class");
        a.add("-no-stdlib"); a.add("-no-reflect"); a.add("-nowarn");
        PrintStream nul = new PrintStream(new ByteArrayOutputStream());
        return compiler.exec(nul, a.toArray(new String[0]));
    }

    // ---------- one-time setup ----------

    static void linkResources() throws Exception {
        Files.createDirectories(genDir);
        Path resZip = workDir.resolve("res.zip");
        run(aapt2, "compile", "--dir", resDir.toString(), "-o", resZip.toString());
        run(aapt2, "link", "--manifest", payloadDir.resolve("AndroidManifest.xml").toString(),
                "-I", androidJar, "--package-id", "0x80", "--allow-reserved-package-id",
                "--min-sdk-version", "30", "--target-sdk-version", "34",
                "--java", genDir.toString(), "-o", resourcesApk.toString(), resZip.toString());
    }

    static void compileRClass() throws Exception {
        List<Path> rJava = new ArrayList<>();
        collect(genDir, ".java", rJava);
        List<String> a = new ArrayList<>(List.of("-classpath", androidJar, "-d", classesDir.toString()));
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
            Thread.sleep(80); // debounce
            // Drain all queued keys so a burst of writes classifies as one save.
            boolean srcChanged = false, resChanged = false;
            List<WatchKey> keys = new ArrayList<>();
            keys.add(key);
            WatchKey k2;
            while ((k2 = ws.poll()) != null) keys.add(k2);
            for (WatchKey kk : keys) {
                Path dir = (Path) kk.watchable();
                if (dir.startsWith(srcDir)) srcChanged = true;
                if (dir.startsWith(resDir)) resChanged = true;
                kk.pollEvents();
                kk.reset();
            }
            // TIER DISPATCH: a code change takes the code path (Tier 1 attempt →
            // Tier 2 fallback); a resource-only change takes Tier 0 (no compiler).
            try {
                if (srcChanged) codeChange();
                else if (resChanged) resourceOnlyChange();
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
        http.setExecutor(null);
        http.start();
        log("listening on 127.0.0.1:8378 for /reloaded");
    }

    static void deploy(Path apk) throws Exception {
        run("sh", spikeRoot.resolve("tools/deploy_payload.sh").toString(), apk.toString());
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
        Path trg = Files.createTempFile("redef", ".trigger");
        Files.writeString(trg, "app.payload.Main"); // agent redefines Main + its synthetics from the dex
        sh("adb push " + dex + " /data/local/tmp/redefine.dex");
        sh("adb push " + trg + " /data/local/tmp/redefine.trigger");
        sh("adb shell run-as " + pkg + " rm -f files/hotswap/result");
        sh("adb shell run-as " + pkg + " cp /data/local/tmp/redefine.dex files/hotswap/redefine.dex");
        // trigger LAST so the dex is fully staged when the agent wakes
        sh("adb shell run-as " + pkg + " cp /data/local/tmp/redefine.trigger files/hotswap/trigger");
        for (int i = 0; i < 60; i++) {
            String r = sh("adb shell run-as " + pkg + " cat files/hotswap/result").trim();
            if (r.endsWith("0")) return true;   // redefined
            if (r.endsWith("1")) return false;  // structural / no match → fallback
            Thread.sleep(15);
        }
        return null; // no agent attached / timed out → fallback
    }

    static String sh(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    // ---------- small helpers ----------

    static void zipInto(Path apk, Path file, String entryName) throws Exception {
        // Use the `zip` CLI to add/replace a single entry (fast, no rewrite of the apk).
        Path staged = apk.getParent().resolve(entryName);
        Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING);
        run("sh", "-c", "cd " + apk.getParent() + " && zip -q -j " + apk.getFileName()
                + " " + entryName);
        Files.deleteIfExists(staged);
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
            walk.filter(p -> p.toString().endsWith(".class")
                            && !p.getFileName().toString().startsWith("R"))
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
