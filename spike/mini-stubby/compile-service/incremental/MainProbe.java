import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.common.ExitCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ADFA-4128 — attribute WHY the real 617-line Main.kt compiles slower than the synthetic
 * benchmark's ~700-LoC point. Three warm K2JVMCompiler full compiles on-device:
 *   A) synthetic ~692-LoC file, stdlib-only classpath      (== the benchmark condition)
 *   B) same synthetic file, classpath += android.jar        (isolates android.jar load cost)
 *   C) the REAL Main.kt (Android framework + org.json), stdlib + android.jar
 * Delta A→B = cost of the big android classpath; B→C = cost of real framework-API type-checking.
 *
 * Args: <workDir> <stdlibJar> <androidJar> <realMainKt>
 */
public class MainProbe {
    static final int WARMUP = 2, TIMED = 5;

    static long now() { return System.nanoTime() / 1_000_000; }

    static void wipe(Path p) throws Exception {
        if (Files.exists(p)) try (Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception e) {} });
        }
        Files.createDirectories(p);
    }

    static String ktMethod(int i) {
        int k = 3 + (i % 5), m = 7 + (i % 11), t = 100 + i;
        return "    fun f" + i + "(x: Int): Int {\n"
             + "        var a = x * " + k + " + " + i + "\n"
             + "        a = a xor (a shr 3)\n"
             + "        val s = \"n" + i + "\" + a + \"_\" + (a % " + m + ")\n"
             + "        if (a > " + t + ") a = a - s.length else a = a + s.length\n"
             + "        var acc = 0\n"
             + "        for (j in 0 until (a and 7)) acc = acc + j * " + k + "\n"
             + "        return acc + s.length + a\n"
             + "    }\n";
    }

    static int genSynth(Path file, int methods) throws Exception {
        StringBuilder b = new StringBuilder("package bench\n");
        for (int c = 0; c * 10 < methods; c++) {
            b.append("class Gen").append(c).append(" {\n");
            for (int j = 0; j < 10 && c * 10 + j < methods; j++) b.append(ktMethod(c * 10 + j));
            b.append("}\n");
        }
        Files.writeString(file, b.toString());
        return (int) b.toString().chars().filter(ch -> ch == '\n').count();
    }

    static long compile(Path src, Path out, String cp) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        long t0 = now();
        ExitCode ec = new K2JVMCompiler().exec(new PrintStream(err),
            "-classpath", cp, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-d", out.toString(), "-nowarn", src.toString());
        long dt = now() - t0;
        if (ec != ExitCode.OK) { System.out.println("  FAILED " + ec + "\n" + err); return -1; }
        return dt;
    }

    static long[] timed(Path src, Path out, String cp) throws Exception {
        List<Long> ts = new ArrayList<>();
        for (int r = 0; r < WARMUP + TIMED; r++) {
            wipe(out);
            long dt = compile(src, out, cp);
            if (dt < 0) return new long[]{-1, -1};
            if (r >= WARMUP) ts.add(dt);
        }
        ts.sort(Long::compareTo);
        return new long[]{ts.get(ts.size() / 2), ts.get(0)};
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        String stdlib = args[1], androidJar = args[2], realMain = args[3];
        Files.createDirectories(work);
        Path out = work.resolve("out");
        Path synth = work.resolve("Gen.kt");
        int loc = genSynth(synth, 75);
        String sep = File.pathSeparator;
        System.out.println("MainProbe JDK " + System.getProperty("java.version")
            + "  synth=" + loc + " LoC  realMain=" + realMain);

        // global warm
        for (int w = 0; w < 3; w++) compile(synth, out, stdlib);

        long[] a = timed(synth, out, stdlib);
        long[] b = timed(synth, out, stdlib + sep + androidJar);
        long[] c = timed(Path.of(realMain), out, stdlib + sep + androidJar);
        System.out.println("A synth stdlib-only        median=" + a[0] + "ms min=" + a[1]);
        System.out.println("B synth +android.jar       median=" + b[0] + "ms min=" + b[1]);
        System.out.println("C real Main.kt +android.jar median=" + c[0] + "ms min=" + c[1]);
        System.out.println("delta A->B (android.jar load)      = " + (b[0] - a[0]) + "ms");
        System.out.println("delta B->C (framework type-check)  = " + (c[0] - b[0]) + "ms");
        System.out.println("DONE");
    }
}
