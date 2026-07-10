import org.jetbrains.kotlin.buildtools.api.*;
import org.jetbrains.kotlin.buildtools.api.jvm.*;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Incremental Kotlin compilation via the Kotlin Build Tools API (CompilationService).
 * Replaces the whole-app {@code K2JVMCompiler.exec()} path: a one-line edit recompiles
 * ~the one changed file instead of every source, keeping the hot loop flat regardless
 * of app size (see demo/INCREMENTAL-RESULTS.md — ~70–130 ms at 600 LoC → 30k LoC).
 *
 * Load-bearing details learned the hard way:
 *  - Drive with {@link SourcesChanges.Known} (the changed files) — {@code ToBeCalculated}
 *    fell back to a full compile (UNKNOWN_CHANGES_IN_GRADLE_INPUTS).
 *  - The {@code shrunkClasspathSnapshot} param MUST be exactly
 *    {@code <rootProjectDir>/shrunk-classpath-snapshot.bin} (the path the engine writes),
 *    or every build silently falls back to non-incremental (CLASSPATH_SNAPSHOT_NOT_FOUND)
 *    while still compiling correctly.
 *  - Runtime deps beyond the compiler: kotlinx-coroutines-core-jvm + trove4j.
 */
final class IncrementalCompiler {

    private final CompilationService svc;
    private final ProjectId projectId;
    private final Path icDir;             // persistent IC caches (source-level)
    private final Path rootDir;           // the engine writes shrunk-classpath-snapshot.bin here
    private final File shrunk;            // == rootDir/shrunk-classpath-snapshot.bin
    private final List<File> cpSnapshots; // snapshots of the fixed classpath (computed once)
    private String lastDiagnostics = "";

    /** @param classpathJars the fixed compile classpath (android.jar, kotlin-stdlib) to snapshot once. */
    IncrementalCompiler(List<String> classpathJars, Path workDir) throws Exception {
        this.svc = CompilationService.loadImplementation(IncrementalCompiler.class.getClassLoader());
        this.projectId = new ProjectId.ProjectUUID(UUID.randomUUID());
        this.icDir = workDir.resolve("ic");
        this.rootDir = workDir;                       // shrunk snapshot lives directly under here
        this.shrunk = workDir.resolve("shrunk-classpath-snapshot.bin").toFile();
        Files.createDirectories(icDir);
        Path snapDir = workDir.resolve("cp-snap");
        Files.createDirectories(snapDir);
        this.cpSnapshots = new ArrayList<>();
        for (String jar : classpathJars) {
            File snap = snapDir.resolve(new File(jar).getName() + ".snap").toFile();
            ClasspathEntrySnapshot es = svc.calculateClasspathSnapshot(
                    new File(jar), ClassSnapshotGranularity.CLASS_MEMBER_LEVEL);
            es.saveSnapshot(snap);
            cpSnapshots.add(snap);
        }
    }

    String diagnostics() { return lastDiagnostics; }

    /**
     * @param allSources    every .kt source (the engine still needs the full set)
     * @param changedFiles  the files that changed since last compile; pass ALL sources on the
     *                      first/cold build so the IC caches get seeded
     * @return true on COMPILATION_SUCCESS
     */
    boolean compile(List<File> allSources, List<File> changedFiles, String classpath,
                    Path out, List<String> extraArgs) {
        StringBuilder diag = new StringBuilder();
        CompilerExecutionStrategyConfiguration strat =
                svc.makeCompilerExecutionStrategyConfiguration().useInProcessStrategy();
        JvmCompilationConfiguration cfg = svc.makeJvmCompilationConfiguration();
        cfg.useLogger(new CollectingLogger(diag));
        ClasspathSnapshotBasedIncrementalJvmCompilationConfiguration icCfg =
                cfg.makeClasspathSnapshotBasedIncrementalCompilationConfiguration();
        icCfg.setRootProjectDir(rootDir.toFile());
        icCfg.setBuildDir(out.toFile());
        // Our classpath is FIXED between provisioning runs (the service re-seeds when it
        // changes), so skip the per-build re-verification of every classpath snapshot —
        // with a real resolved classpath (267 entries) that check dominates the
        // incremental cost. Only valid after the first build has written the shrunk
        // snapshot (before that the engine needs the full comparison to seed).
        if (shrunk.exists()) icCfg.assureNoClasspathSnapshotsChanges(true);
        ClasspathSnapshotBasedIncrementalCompilationApproachParameters params =
                new ClasspathSnapshotBasedIncrementalCompilationApproachParameters(cpSnapshots, shrunk);
        SourcesChanges changes = new SourcesChanges.Known(new ArrayList<>(changedFiles), Collections.emptyList());
        cfg.useIncrementalCompilation(icDir.toFile(), changes, params, icCfg);

        List<String> a = new ArrayList<>();
        a.add("-classpath"); a.add(classpath);
        a.add("-d"); a.add(out.toString());
        a.add("-jvm-target"); a.add("17");
        a.add("-module-name"); a.add("payload");
        a.addAll(extraArgs);
        a.add("-no-stdlib"); a.add("-no-reflect"); a.add("-nowarn");

        CompilationResult r = svc.compileJvm(projectId, strat, cfg, allSources, a);
        lastDiagnostics = diag.toString();
        return r == CompilationResult.COMPILATION_SUCCESS;
    }

    /** Collects compiler error output so a COMPILATION_ERROR reports file/line (self-heal loop). */
    private static final class CollectingLogger implements KotlinLogger {
        private final StringBuilder sb;
        CollectingLogger(StringBuilder sb) { this.sb = sb; }
        public boolean isDebugEnabled() { return false; }
        public void error(String m, Throwable t) { sb.append(m).append('\n'); }
        public void warn(String m) {}
        public void info(String m) {}
        public void debug(String m) {}
        public void lifecycle(String m) {}
    }
}
