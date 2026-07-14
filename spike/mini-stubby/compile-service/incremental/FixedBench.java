import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.common.ExitCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * ADFA-4128 — compile a FIXED source file repeatedly (warm) and report the median full-compile
 * time. Contents never change, so this isolates the effect of JVM flags (heap/GC, set at launch)
 * and extra kotlinc flags (passed as argv) and the compiler version (swap the -cp jars).
 *
 * Args: <workDir> <stdlibJar> <androidJar> <srcFile> [extra kotlinc args...]
 * Prints: RESULT median=<ms> min=<ms> gc=<name> maxheap=<MB>
 */
public class FixedBench {
    static final int WARMUP = 2, TIMED = 5;
    static long now() { return System.nanoTime() / 1_000_000; }

    static void wipe(Path p) throws Exception {
        if (Files.exists(p)) try (Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception e) {} });
        }
        Files.createDirectories(p);
    }

    static long compile(Path src, Path out, String cp, List<String> extra) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> a = new ArrayList<>(List.of(
            "-classpath", cp, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-d", out.toString(), "-nowarn"));
        a.addAll(extra);
        a.add(src.toString());
        long t0 = now();
        ExitCode ec = new K2JVMCompiler().exec(new PrintStream(err), a.toArray(new String[0]));
        long dt = now() - t0;
        if (ec != ExitCode.OK) { System.out.println("  FAILED " + ec + "\n" + err); return -1; }
        return dt;
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        String stdlib = args[1], androidJar = args[2], src = args[3];
        List<String> extra = args.length > 4 ? Arrays.asList(args).subList(4, args.length) : List.of();
        Files.createDirectories(work);
        Path out = work.resolve("out");
        String cp = stdlib + File.pathSeparator + androidJar;

        for (int w = 0; w < WARMUP; w++) { wipe(out); if (compile(Path.of(src), out, cp, extra) < 0) return; }
        List<Long> ts = new ArrayList<>();
        for (int r = 0; r < TIMED; r++) { wipe(out); long dt = compile(Path.of(src), out, cp, extra); if (dt < 0) return; ts.add(dt); }
        ts.sort(Long::compareTo);
        String gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream().map(b -> b.getName()).reduce((x, y) -> x + "+" + y).orElse("?");
        long maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        System.out.println("RESULT median=" + ts.get(ts.size() / 2) + " min=" + ts.get(0)
            + " gc=" + gc.replace(" ", "") + " maxheap=" + maxHeap
            + " extra=" + String.join(" ", extra));
    }
}
