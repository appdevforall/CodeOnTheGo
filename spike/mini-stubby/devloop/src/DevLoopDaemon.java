import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mini-Stubby devloop daemon (ADFA-4128 phase 2, component A).
 *
 * Long-running warm compile loop for the payload "user app":
 *   - watches payload/java, payload/res, payload/assets recursively (80 ms debounce)
 *   - javac in-process (warm JVM), d8 in-process via reflection on d8.jar
 *   - aapt2 compile+link as a subprocess ONLY when res/assets changed (cached otherwise)
 *   - packages resource apk + classes.dex, deploys via tools/deploy_payload.sh
 *   - HTTP on 127.0.0.1:8378: POST /reloaded (end-to-end timing), POST /rebuild (force full)
 *
 * Usage: DevLoopDaemon &lt;spike-root&gt; [--dry-run]
 * System properties (set by run_devloop.sh from tools/env.sh): platform.jar, aapt2.bin
 *
 * With --dry-run the deploy step is skipped (deploy=skipped) so the full
 * watch-&gt;build cycle can be verified without touching the device.
 */
public class DevLoopDaemon {

    private static final int DEBOUNCE_MS = 80;
    private static final int HTTP_PORT = 8378;
    private static final String ARROW = "→"; // → (escape keeps source encoding-proof)

    // Paths
    private final Path spikeRoot;
    private final Path javaRoot;
    private final Path resRoot;
    private final Path assetsRoot;
    private final Path manifest;
    private final Path cacheDir;      // build/devloop
    private final Path genDir;        // cached generated R.java
    private final Path classesDir;
    private final Path dexDir;
    private final Path resZip;
    private final Path resourcesApk;  // cached linked resource apk (no classes.dex)
    private final Path outApk;
    private final Path deployScript;
    private final Path platformJar;
    private final String aapt2Bin;
    private final boolean dryRun;

    // Change state (guarded by lock)
    private final Object lock = new Object();
    private boolean javaDirty = false;
    private boolean resDirty = false;
    private long lastEventMs = 0;
    private long pendingSaveWallMs = 0; // wall time of the FIRST event in the current batch

    // Most recent successful deploy (for the /reloaded end-to-end line)
    private volatile long lastDeploySaveWallMs = 0;

    private final JavaCompiler compiler;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: DevLoopDaemon <spike-root> [--dry-run]");
            System.exit(2);
        }
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            if ("--dry-run".equals(args[i])) dryRun = true;
            else { System.err.println("unknown arg: " + args[i]); System.exit(2); }
        }
        new DevLoopDaemon(Paths.get(args[0]).toAbsolutePath().normalize(), dryRun).run();
    }

    private DevLoopDaemon(Path spikeRoot, boolean dryRun) {
        this.spikeRoot = spikeRoot;
        this.dryRun = dryRun;
        Path payload = spikeRoot.resolve("payload");
        this.javaRoot = payload.resolve("java");
        this.resRoot = payload.resolve("res");
        this.assetsRoot = payload.resolve("assets");
        this.manifest = payload.resolve("AndroidManifest.xml");
        this.cacheDir = spikeRoot.resolve("build/devloop");
        this.genDir = cacheDir.resolve("gen");
        this.classesDir = cacheDir.resolve("classes");
        this.dexDir = cacheDir.resolve("dex");
        this.resZip = cacheDir.resolve("res.zip");
        this.resourcesApk = cacheDir.resolve("resources.apk");
        this.outApk = cacheDir.resolve("payload-devloop.apk");
        this.deployScript = spikeRoot.resolve("tools/deploy_payload.sh");

        String pj = System.getProperty("platform.jar");
        String a2 = System.getProperty("aapt2.bin");
        if (pj == null || a2 == null) {
            throw new IllegalStateException(
                "missing -Dplatform.jar / -Daapt2.bin (run via run_devloop.sh, which sources tools/env.sh)");
        }
        this.platformJar = Paths.get(pj);
        this.aapt2Bin = a2;

        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("no system java compiler (need a JDK, not a JRE)");
    }

    private void run() throws Exception {
        Files.createDirectories(cacheDir);
        System.out.println("DEVLOOP daemon starting (spike=" + spikeRoot + ", dryRun=" + dryRun + ")");

        startHttpServer();
        startWatcher();

        // Initial full build at startup: warms javac/d8 and populates the aapt2 cache.
        synchronized (lock) {
            javaDirty = true;
            resDirty = true;
            pendingSaveWallMs = System.currentTimeMillis();
            lastEventMs = pendingSaveWallMs - DEBOUNCE_MS; // no debounce wait for the initial build
            lock.notifyAll();
        }

        buildLoop(); // never returns
    }

    // ------------------------------------------------------------------ watch

    private void startWatcher() throws IOException {
        WatchService ws = spikeRoot.getFileSystem().newWatchService();
        Map<WatchKey, Path> keyDirs = new HashMap<>();
        for (Path root : new Path[] { javaRoot, resRoot, assetsRoot }) {
            if (Files.isDirectory(root)) registerTree(ws, keyDirs, root);
            else System.out.println("DEVLOOP warn: watch root missing, skipping: " + root);
        }
        Thread t = new Thread(() -> watchLoop(ws, keyDirs), "devloop-watcher");
        t.setDaemon(true);
        t.start();
        System.out.println("DEVLOOP watching " + javaRoot + ", " + resRoot + ", " + assetsRoot);
    }

    private void registerTree(WatchService ws, Map<WatchKey, Path> keyDirs, Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(ws,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                keyDirs.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop(WatchService ws, Map<WatchKey, Path> keyDirs) {
        while (true) {
            WatchKey key;
            try {
                key = ws.take();
            } catch (InterruptedException e) {
                return;
            }
            Path dir = keyDirs.get(key);
            boolean sawJava = false, sawRes = false;
            for (WatchEvent<?> ev : key.pollEvents()) {
                if (ev.kind() == StandardWatchEventKinds.OVERFLOW) {
                    sawJava = true; sawRes = true;
                    continue;
                }
                Path child = dir == null ? null : dir.resolve((Path) ev.context());
                if (child == null) continue;
                // Newly created directories must be registered so nested edits are seen.
                if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    try {
                        registerTree(ws, keyDirs, child);
                    } catch (IOException ioe) {
                        System.out.println("DEVLOOP warn: failed to watch new dir " + child + ": " + ioe);
                    }
                }
                if (child.startsWith(javaRoot)) sawJava = true;
                else sawRes = true; // res/ or assets/
            }
            boolean valid = key.reset();
            if (!valid) keyDirs.remove(key);
            if (sawJava || sawRes) markDirty(sawJava, sawRes);
        }
    }

    private void markDirty(boolean java, boolean res) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (!javaDirty && !resDirty) pendingSaveWallMs = now; // first event of this batch = "save" time
            if (java) javaDirty = true;
            if (res) resDirty = true;
            lastEventMs = now;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ build

    private void buildLoop() {
        while (true) {
            boolean doRes;
            long saveWall;
            try {
                synchronized (lock) {
                    while (!javaDirty && !resDirty) lock.wait();
                    // Debounce: wait until DEBOUNCE_MS have passed since the last event.
                    while (true) {
                        long quiet = System.currentTimeMillis() - lastEventMs;
                        if (quiet >= DEBOUNCE_MS) break;
                        lock.wait(DEBOUNCE_MS - quiet);
                    }
                    doRes = resDirty;
                    saveWall = pendingSaveWallMs;
                    javaDirty = false;
                    resDirty = false;
                }
            } catch (InterruptedException e) {
                return;
            }
            try {
                build(doRes, saveWall);
            } catch (Exception e) {
                System.out.println("DEVLOOP build failed: " + e.getMessage());
            }
        }
    }

    private void build(boolean resChanged, long saveWallMs) throws Exception {
        long t0 = System.nanoTime();

        // 1. aapt2 (subprocess) — only when res/assets changed or the cache is cold.
        long aapt2Ms = -1;
        boolean cacheCold = !Files.isRegularFile(resourcesApk) || !Files.isDirectory(genDir);
        if (resChanged || cacheCold) {
            long a0 = System.nanoTime();
            runAapt2();
            aapt2Ms = msSince(a0);
        }

        // 2. javac in-process (warm JVM). Compile ALL payload sources + generated R.java.
        long j0 = System.nanoTime();
        if (!compileJava()) {
            System.out.println("DEVLOOP build aborted: javac failed (d8/deploy skipped)");
            return;
        }
        long javacMs = msSince(j0);

        // 3. d8 in-process via reflection (javac already succeeded, so d8 errors are rare).
        long d0 = System.nanoTime();
        runD8();
        long d8Ms = msSince(d0);

        // 4. package: cached resource apk + fresh classes.dex.
        long p0 = System.nanoTime();
        Files.copy(resourcesApk, outApk, StandardCopyOption.REPLACE_EXISTING);
        runOrThrow(List.of("zip", "-q", "-j", outApk.toString(), "classes.dex"), dexDir, "zip");
        long packMs = msSince(p0);

        // 5. deploy (skipped in --dry-run).
        String deployStr;
        if (dryRun) {
            deployStr = "skipped";
        } else {
            long dep0 = System.nanoTime();
            runOrThrow(List.of("sh", deployScript.toString(), outApk.toString()), spikeRoot, "deploy");
            deployStr = msSince(dep0) + "ms";
        }

        lastDeploySaveWallMs = saveWallMs;
        long totalMs = System.currentTimeMillis() - saveWallMs;
        // Fall back to build-only total if the save timestamp is somehow unusable.
        if (totalMs < 0) totalMs = msSince(t0);
        System.out.println("DEVLOOP save" + ARROW + "deployed total=" + totalMs + "ms"
                + " (javac=" + javacMs + "ms"
                + " d8=" + d8Ms + "ms"
                + " aapt2=" + (aapt2Ms < 0 ? "cached" : aapt2Ms + "ms")
                + " pack=" + packMs + "ms"
                + " deploy=" + deployStr + ")");
    }

    private void runAapt2() throws Exception {
        Files.createDirectories(genDir);
        runOrThrow(List.of(aapt2Bin, "compile", "--dir", resRoot.toString(), "-o", resZip.toString()),
                spikeRoot, "aapt2 compile");
        List<String> link = new ArrayList<>(List.of(
                aapt2Bin, "link",
                "--manifest", manifest.toString(),
                "-I", platformJar.toString(),
                "--package-id", "0x80",
                "--min-sdk-version", "30", "--target-sdk-version", "34",
                "--java", genDir.toString()));
        if (Files.isDirectory(assetsRoot)) {
            link.add("-A");
            link.add(assetsRoot.toString());
        }
        link.add("-o");
        link.add(resourcesApk.toString());
        link.add(resZip.toString());
        runOrThrow(link, spikeRoot, "aapt2 link");
    }

    /** @return true when compilation succeeded. */
    private boolean compileJava() throws IOException {
        List<Path> sources = new ArrayList<>();
        collectFiles(javaRoot, ".java", sources);
        collectFiles(genDir, ".java", sources);
        if (sources.isEmpty()) {
            System.out.println("DEVLOOP warn: no java sources found");
            return false;
        }
        deleteRecursively(classesDir); // avoid stale classes from renamed/deleted files
        Files.createDirectories(classesDir);

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of(
                    "-classpath", platformJar.toString(),
                    "-d", classesDir.toString(),
                    "-encoding", "UTF-8",
                    "-proc:none");
            Boolean ok = compiler.getTask(null, fm, diags, options, null, units).call();
            if (ok == null || !ok) {
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        System.out.println("DEVLOOP javac error: " + d);
                    }
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Runs D8 in-process via reflection on the D8Command API (d8.jar is on the daemon
     * classpath courtesy of run_devloop.sh; this file compiles without it present).
     * The command API throws CompilationFailedException instead of calling System.exit,
     * so a d8 failure can never kill the daemon.
     */
    private void runD8() throws Exception {
        deleteRecursively(dexDir);
        Files.createDirectories(dexDir);
        List<Path> classFiles = new ArrayList<>();
        collectFiles(classesDir, ".class", classFiles);

        Class<?> d8Cls = Class.forName("com.android.tools.r8.D8");
        Class<?> cmdCls = Class.forName("com.android.tools.r8.D8Command");
        Class<?> outputModeCls = Class.forName("com.android.tools.r8.OutputMode");

        Object builder = cmdCls.getMethod("builder").invoke(null);
        Class<?> b = builder.getClass();
        b.getMethod("addProgramFiles", Collection.class).invoke(builder, classFiles);
        b.getMethod("addLibraryFiles", Collection.class).invoke(builder, List.of(platformJar));
        b.getMethod("setMinApiLevel", int.class).invoke(builder, 30);
        Object dexIndexed = outputModeCls.getField("DexIndexed").get(null);
        b.getMethod("setOutput", Path.class, outputModeCls).invoke(builder, dexDir, dexIndexed);
        Object command = b.getMethod("build").invoke(builder);

        Method runMethod = d8Cls.getMethod("run", cmdCls);
        try {
            runMethod.invoke(null, command);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            throw new Exception("d8: " + cause.getMessage(), cause);
        }
    }

    // ------------------------------------------------------------------ http

    private void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", HTTP_PORT), 0);
        server.createContext("/reloaded", this::handleReloaded);
        server.createContext("/rebuild", this::handleRebuild);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "devloop-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("DEVLOOP listening on 127.0.0.1:" + HTTP_PORT + " (POST /reloaded, POST /rebuild)");
    }

    private void handleReloaded(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "POST only");
                return;
            }
            String body = readBody(ex);
            long gen = extractLong(body, "gen", -1);
            long reloadMs = extractLong(body, "reloadMs", -1);
            long saveWall = lastDeploySaveWallMs;
            if (saveWall > 0) {
                long e2e = System.currentTimeMillis() - saveWall;
                System.out.println("DEVLOOP save" + ARROW + "rendered end-to-end " + e2e + "ms"
                        + " (gen " + gen + ", device reload " + reloadMs + " ms)");
            } else {
                System.out.println("DEVLOOP /reloaded received (gen " + gen + ", device reload "
                        + reloadMs + " ms) but no deploy recorded yet");
            }
            respond(ex, 200, "ok");
        } finally {
            ex.close();
        }
    }

    private void handleRebuild(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "POST only");
                return;
            }
            readBody(ex); // drain
            System.out.println("DEVLOOP /rebuild requested: forcing full rebuild");
            markDirty(true, true);
            respond(ex, 200, "ok");
        } finally {
            ex.close();
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Minimal JSON number extraction — bodies are trusted local tooling messages. */
    private static long extractLong(String json, String key, long dflt) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : dflt;
    }

    // ------------------------------------------------------------------ util

    private static long msSince(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000L;
    }

    private static void collectFiles(Path root, String suffix, List<Path> out) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix))
             .sorted()
             .forEach(out::add);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void runOrThrow(List<String> cmd, Path cwd, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (InputStream in = p.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            output = out.toString(StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new Exception(label + " failed (exit " + code + "):\n" + output.trim());
        }
    }
}
