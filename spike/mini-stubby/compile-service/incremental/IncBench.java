import org.jetbrains.kotlin.buildtools.api.*;
import org.jetbrains.kotlin.buildtools.api.jvm.*;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Proves incremental Kotlin compilation via the Build Tools API:
 *   - generate an N-file Kotlin "app"
 *   - cold full compile (populates IC caches)
 *   - edit ONE file's method body
 *   - incremental compile (should recompile ~1 file, fast)
 *   - correctness: incremental output == a clean build of the edited sources
 *
 * Args: <workDir> <androidJar> <stdlibJar> <numFiles> <linesPerFile>
 */
public class IncBench {

    static final class Log implements KotlinLogger {
        public boolean isDebugEnabled() { return true; }
        public void error(String m, Throwable t) { System.out.println("  [kc-error] " + m); }
        public void warn(String m) {}
        public void info(String m) { keep(m); }
        public void debug(String m) { keep(m); }
        public void lifecycle(String m) { keep(m); }
        void keep(String m) {
            String l = m.toLowerCase();
            if (l.contains("incremental") || l.contains("recompil") || l.contains("dirty")
                || l.contains("compiling ") || l.contains("sources to") || l.contains("changed"))
                System.out.println("    · " + (m.length() > 160 ? m.substring(0, 160) : m));
        }
    }

    static long now() { return System.nanoTime() / 1_000_000; }
    static long countFiles(Path p) {
        try { return Files.walk(p).filter(Files::isRegularFile).count(); } catch (Exception e) { return -1; }
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        String androidJar = args[1], stdlib = args[2];
        int nFiles = Integer.parseInt(args[3]);
        int lines = Integer.parseInt(args[4]);
        String cp = androidJar + File.pathSeparator + stdlib;

        Path src = work.resolve("src");
        Path outInc = work.resolve("out-inc");     // incremental target
        Path outClean = work.resolve("out-clean"); // clean-build oracle
        Path icDir = work.resolve("ic");
        Path snapDir = work.resolve("cp-snap");
        for (Path p : List.of(src, outInc, outClean, icDir, snapDir)) {
            if (Files.exists(p)) deleteRec(p);
            Files.createDirectories(p);
        }

        genApp(src, nFiles, lines);
        List<File> sources = Files.walk(src).filter(p -> p.toString().endsWith(".kt"))
                .map(Path::toFile).collect(Collectors.toList());
        System.out.println("generated " + sources.size() + " .kt files (~" + (nFiles * lines) + " LoC)");

        CompilationService svc = CompilationService.loadImplementation(IncBench.class.getClassLoader());
        System.out.println("BTA impl: " + svc.getCompilerVersion());

        // One-time: snapshot the fixed classpath.
        long s0 = now();
        List<File> cpSnaps = new ArrayList<>();
        for (String entry : new String[]{androidJar, stdlib}) {
            File snap = snapDir.resolve(new File(entry).getName() + ".snap").toFile();
            ClasspathEntrySnapshot es = svc.calculateClasspathSnapshot(new File(entry),
                    ClassSnapshotGranularity.CLASS_MEMBER_LEVEL);
            es.saveSnapshot(snap);
            cpSnaps.add(snap);
        }
        File shrunk = work.resolve("shrunk-classpath-snapshot.bin").toFile();
        System.out.println("classpath snapshot (one-time): " + (now() - s0) + " ms");

        ProjectId proj = new ProjectId.ProjectUUID(UUID.randomUUID());

        // 1. COLD full compile: tell IC ALL sources are new (builds caches).
        SourcesChanges coldChanges = new SourcesChanges.Known(sources, Collections.emptyList());
        long c0 = now();
        CompilationResult r1 = compile(svc, proj, sources, coldChanges, cp, outInc, icDir, cpSnaps, shrunk);
        System.out.println("COLD full compile: " + (now() - c0) + " ms  -> " + r1);

        // 2+3. Edit ONE file, then INCREMENTAL compile telling IC exactly that file changed.
        long incMs = 0;
        for (int k = 0; k <= 3; k++) {
            Path ef = src.resolve("Leaf" + k + ".kt");
            String eb = Files.readString(ef).replaceFirst("val a = x \\* ", "val a = x + " + (k + 1) + " * ");
            Files.writeString(ef, eb);
            SourcesChanges known = new SourcesChanges.Known(List.of(ef.toFile()), Collections.emptyList());
            long i0 = now();
            CompilationResult r2 = compile(svc, proj, sources, known, cp, outInc, icDir, cpSnaps, shrunk);
            incMs = now() - i0;
            System.out.println("INCREMENTAL edit#" + (k + 1) + " (1 file, Known): " + incMs
                    + " ms  -> " + r2 + "  [shrunk exists=" + shrunk.exists() + " len=" + shrunk.length()
                    + " icDirFiles=" + countFiles(icDir) + "]");
        }

        // 4. For comparison: a FULL compile of the edited sources (fresh IC = no reuse).
        Path icDir2 = work.resolve("ic2");
        if (Files.exists(icDir2)) deleteRec(icDir2); Files.createDirectories(icDir2);
        SourcesChanges allNew = new SourcesChanges.Known(sources, Collections.emptyList());
        long f0 = now();
        compile(svc, proj, sources, allNew, cp, outClean, icDir2, cpSnaps, shrunk);
        long fullMs = now() - f0;
        System.out.println("FULL compile of edited sources (fresh): " + fullMs + " ms");

        // 5. Correctness: incremental output classes == clean-build classes.
        boolean same = sameClasses(outInc, outClean);
        System.out.println("CORRECTNESS incremental==clean: " + (same ? "PASS" : "FAIL"));

        System.out.println(String.format(
            "SPEEDUP incremental vs full: %.1fx (%d ms -> %d ms)",
            (double) fullMs / Math.max(1, incMs), fullMs, incMs));
        svc.finishProjectCompilation(proj);
    }

    static CompilationResult compile(CompilationService svc, ProjectId proj, List<File> sources,
            SourcesChanges changes, String cp, Path out, Path icDir, List<File> cpSnaps, File shrunk) {
        CompilerExecutionStrategyConfiguration strat =
                svc.makeCompilerExecutionStrategyConfiguration().useInProcessStrategy();
        JvmCompilationConfiguration cfg = svc.makeJvmCompilationConfiguration();
        cfg.useLogger(new Log());
        ClasspathSnapshotBasedIncrementalJvmCompilationConfiguration icCfg =
                cfg.makeClasspathSnapshotBasedIncrementalCompilationConfiguration();
        // Persist build history so subsequent builds can diff against it, and tell the
        // engine our fixed classpath (android.jar + stdlib) never changes — both are
        // needed or it falls back to non-incremental (UNKNOWN_CHANGES_IN_GRADLE_INPUTS).
        icCfg.setRootProjectDir(icDir.getParent().toFile());
        icCfg.setBuildDir(out.toFile());
        if (shrunk.exists()) icCfg.assureNoClasspathSnapshotsChanges(true);  // fixed cp: stable after 1st build
        ClasspathSnapshotBasedIncrementalCompilationApproachParameters params =
                new ClasspathSnapshotBasedIncrementalCompilationApproachParameters(cpSnaps, shrunk);
        cfg.useIncrementalCompilation(icDir.toFile(), changes, params, icCfg);

        List<String> a = new ArrayList<>();
        a.add("-classpath"); a.add(cp);
        a.add("-d"); a.add(out.toString());
        a.add("-jvm-target"); a.add("17");
        a.add("-module-name"); a.add("payload");
        a.add("-Xlambdas=class"); a.add("-Xsam-conversions=class");
        a.add("-no-stdlib"); a.add("-no-reflect"); a.add("-nowarn");
        return svc.compileJvm(proj, strat, cfg, sources, a);
    }

    // --- generate a multi-file app with a real dependency graph ---
    static void genApp(Path src, int nFiles, int lines) throws Exception {
        int leaves = Math.max(1, nFiles - 1);
        StringBuilder main = new StringBuilder("package app.payload\n\nobject Main {\n  fun sum(): Int {\n    var s = 0\n");
        for (int i = 0; i < leaves; i++) {
            main.append("    s += Leaf").append(i).append(".value()\n");
            StringBuilder lf = new StringBuilder("package app.payload\n\nobject Leaf").append(i).append(" {\n");
            lf.append("  fun value(): Int {\n    return ").append(i == 0 ? "0 // MARK" : String.valueOf(i)).append("\n  }\n");
            for (int m = 0; m < Math.max(1, lines / 6); m++) {
                lf.append("  fun f").append(m).append("(x: Int): Int {\n")
                  .append("    val a = x * ").append(m + 1).append("\n")
                  .append("    val b = a + ").append(i).append("\n")
                  .append("    return b - ").append(m).append("\n  }\n");
            }
            lf.append("}\n");
            Files.writeString(src.resolve("Leaf" + i + ".kt"), lf.toString());
        }
        main.append("    return s\n  }\n}\n");
        Files.writeString(src.resolve("Main.kt"), main.toString());
    }

    static boolean sameClasses(Path a, Path b) throws Exception {
        Map<String, Long> ca = classCrcs(a), cb = classCrcs(b);
        if (!ca.keySet().equals(cb.keySet())) {
            System.out.println("  class-set differs: only-in-inc=" + minus(ca.keySet(), cb.keySet())
                    + " only-in-clean=" + minus(cb.keySet(), ca.keySet()));
            return false;
        }
        return ca.equals(cb);
    }
    static java.util.Set<String> minus(java.util.Set<String> x, java.util.Set<String> y) {
        var s = new java.util.TreeSet<>(x); s.removeAll(y); return s;
    }
    static Map<String, Long> classCrcs(Path root) throws Exception {
        Map<String, Long> m = new TreeMap<>();
        if (!Files.exists(root)) return m;
        Files.walk(root).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
            try {
                java.util.zip.CRC32 c = new java.util.zip.CRC32();
                c.update(Files.readAllBytes(p));
                m.put(root.relativize(p).toString(), c.getValue());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        return m;
    }
    static void deleteRec(Path p) throws Exception {
        if (!Files.exists(p)) return;
        Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception e) {} });
    }
}
