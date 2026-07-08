import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;

import java.io.PrintStream;

/**
 * On-device warm-compile benchmark for Kotlin (ADFA-4128). ONE long-lived JVM,
 * the Kotlin compiler invoked in-process N times — the warm-service equivalent of
 * the Kotlin Gradle daemon, minus Gradle overhead. Mirrors WarmCompileBench (javac+d8).
 * Args: <android.jar> <src.kt> <workDir> <iterations>
 */
public class KotlinWarmBench {
    public static void main(String[] args) throws Exception {
        String androidJar = args[0];
        String src = args[1];
        String work = args[2];
        int iters = Integer.parseInt(args[3]);
        String stdlib = args[4];   // kotlin-stdlib.jar path

        String cp = androidJar + java.io.File.pathSeparator + stdlib;

        for (int i = 1; i <= iters; i++) {
            String out = work + "/kout" + i;
            // Fresh compiler per request (like the Kotlin daemon) but the JVM
            // stays warm across iterations — that's the warm-service number.
            K2JVMCompiler compiler = new K2JVMCompiler();
            // First iter prints diagnostics to stderr; rest are silenced.
            PrintStream msgs = (i == 1) ? System.err : new PrintStream(
                    new java.io.OutputStream() { public void write(int b) {} });
            long t0 = System.nanoTime();
            ExitCode code = compiler.exec(msgs,
                    src,
                    "-classpath", cp,
                    "-d", out,
                    "-jvm-target", "17",
                    "-no-stdlib",
                    "-no-reflect",
                    "-nowarn");
            long t1 = System.nanoTime();
            System.out.println("iter " + i + ": kotlinc=" + ((t1 - t0) / 1_000_000)
                    + "ms exit=" + code);
        }
    }
}
