import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.common.ExitCode;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.JavaFileObject;

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
 * ADFA-4128 — Java-vs-Kotlin FULL-compile time vs file size, warm & in-process.
 *
 * Compiles a single self-contained source file of N lines of *real* code (arithmetic +
 * string ops across methods/classes) at several sizes, for both languages, using the SAME
 * JVM they run on-device:
 *   - Kotlin: {@link K2JVMCompiler}#exec (full compile, no IC) — kotlin-compiler-embeddable 2.0.21.
 *   - Java:   {@code javax.tools} system compiler (javac) via ToolProvider.
 *
 * Methodology (fair, warm): a global warmup per language, then per size W=1 warmup + R=5 timed
 * compiles, median + min reported. Output dir is wiped OUTSIDE the timed region so only compile
 * time is measured. Both languages get identical method counts so LoC matches at each size.
 *
 * This is FULL compile (whole file) — the daemon's fast loop uses incremental (changed-file)
 * which is ~flat with size (see INCREMENTAL-RESULTS.md); this curve shows how a from-scratch
 * per-file compile scales, and how the two compilers compare.
 *
 * Args: <workDir> <kotlinStdlibJar>
 * Emits CSV to stdout: RESULT,<lang>,<methods>,<loc>,<median_ms>,<min_ms>,<runs>
 */
public class LangBench {

    static final int[] SIZES = {50, 100, 200, 300, 400, 600, 800, 1000};
    static final int WARMUP = 1, TIMED = 5;
    static final PrintStream NUL = new PrintStream(new ByteArrayOutputStream());

    static long now() { return System.nanoTime() / 1_000_000; }

    static void wipe(Path p) throws Exception {
        if (Files.exists(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                 .forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception e) {} });
            }
        }
        Files.createDirectories(p);
    }

    /** One Kotlin method, ~8 lines of genuine work. */
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

    /** The matched Java method, same 8 lines of work. */
    static String javaMethod(int i) {
        int k = 3 + (i % 5), m = 7 + (i % 11), t = 100 + i;
        return "    int f" + i + "(int x) {\n"
             + "        int a = x * " + k + " + " + i + ";\n"
             + "        a = a ^ (a >> 3);\n"
             + "        String s = \"n" + i + "\" + a + \"_\" + (a % " + m + ");\n"
             + "        if (a > " + t + ") a = a - s.length(); else a = a + s.length();\n"
             + "        int acc = 0;\n"
             + "        for (int j = 0; j < (a & 7); j++) acc = acc + j * " + k + ";\n"
             + "        return acc + s.length() + a;\n"
             + "    }\n";
    }

    /** Generate a source file of ~`methods` methods (10 per class). Returns actual LoC. */
    static int genSource(Path file, int methods, boolean kotlin) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append(kotlin ? "package bench\n" : "package bench;\n");
        int perClass = 10;
        for (int c = 0; c * perClass < methods; c++) {
            b.append(kotlin ? "class Gen" + c + " {\n" : "class Gen" + c + " {\n");
            for (int j = 0; j < perClass && c * perClass + j < methods; j++) {
                int i = c * perClass + j;
                b.append(kotlin ? ktMethod(i) : javaMethod(i));
            }
            b.append("}\n");
        }
        String src = b.toString();
        Files.writeString(file, src);
        return (int) src.chars().filter(ch -> ch == '\n').count();
    }

    static boolean compileKt(Path src, Path out, String stdlib) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExitCode ec = new K2JVMCompiler().exec(new PrintStream(err),
            "-classpath", stdlib, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-d", out.toString(), "-nowarn", src.toString());
        if (ec != ExitCode.OK) {
            System.out.println("  KT compile FAILED: " + ec + "\n" + err);
            return false;
        }
        return true;
    }

    static final JavaCompiler JC = ToolProvider.getSystemJavaCompiler();
    static boolean compileJava(Path src, Path out) throws Exception {
        StandardJavaFileManager fm = JC.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(List.of(src.toFile()));
        boolean ok = JC.getTask(java.io.Writer.nullWriter(), fm, d -> {}, List.of("-d", out.toString(), "-nowarn"), null, units).call();
        fm.close();
        if (!ok) System.out.println("  JAVA compile FAILED for " + src);
        return ok;
    }

    /** Median of `TIMED` warm compiles (after WARMUP), wiping out-dir outside the timed region. */
    static long[] timeCompile(boolean kotlin, Path src, Path out, String stdlib) throws Exception {
        List<Long> ts = new ArrayList<>();
        for (int r = 0; r < WARMUP + TIMED; r++) {
            wipe(out);
            long t0 = now();
            boolean ok = kotlin ? compileKt(src, out, stdlib) : compileJava(src, out);
            long dt = now() - t0;
            if (!ok) return new long[]{-1, -1};
            if (r >= WARMUP) ts.add(dt);
        }
        ts.sort(Long::compareTo);
        long median = ts.get(ts.size() / 2), min = ts.get(0);
        return new long[]{median, min};
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        String stdlib = args[1];
        Files.createDirectories(work);
        System.out.println("LangBench: JDK " + System.getProperty("java.version")
            + "  kotlinc=K2JVMCompiler(embeddable 2.0.21)  javac=" + (JC != null ? "system" : "MISSING"));
        if (JC == null) { System.out.println("!! no system Java compiler (JRE, not JDK?)"); return; }

        Path ktOut = work.resolve("kt-out"), jOut = work.resolve("j-out");
        Path ktSrc = work.resolve("Gen.kt"), jSrc = work.resolve("Gen.java");

        // Global warmup: hammer a mid-size file a few times per language so the JIT is warm.
        System.out.println("warming up…");
        genSource(ktSrc, 25, true); genSource(jSrc, 25, false);
        for (int w = 0; w < 3; w++) { compileKt(ktSrc, ktOut, stdlib); compileJava(jSrc, jOut); }

        System.out.println("RESULT,lang,methods,loc,median_ms,min_ms,runs");
        for (int size : SIZES) {
            int methods = Math.max(4, Math.round(size / 8f));
            int ktLoc = genSource(ktSrc, methods, true);
            int jLoc  = genSource(jSrc, methods, false);
            long[] kt = timeCompile(true, ktSrc, ktOut, stdlib);
            long[] j  = timeCompile(false, jSrc, jOut, stdlib);
            System.out.println("RESULT,kotlin," + methods + "," + ktLoc + "," + kt[0] + "," + kt[1] + "," + TIMED);
            System.out.println("RESULT,java,"   + methods + "," + jLoc  + "," + j[0]  + "," + j[1]  + "," + TIMED);
            System.out.flush();
        }
        System.out.println("DONE");
    }
}
