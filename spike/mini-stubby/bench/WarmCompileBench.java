import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * On-device warm-compile benchmark for ADFA-4128. Mimics the devloop daemon:
 * ONE long-lived JVM, javac via javax.tools in-process, D8 via its API
 * in-process. Args: <android.jar> <srcDir> <workDir> <iterations>
 */
public class WarmCompileBench {
    public static void main(String[] args) throws Exception {
        Path androidJar = Path.of(args[0]);
        Path srcDir = Path.of(args[1]);
        Path work = Path.of(args[2]);
        int iters = Integer.parseInt(args[3]);

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null);

        List<File> sources = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(srcDir, "*.java")) {
            for (Path p : ds) sources.add(p.toFile());
        }
        System.out.println("sources: " + sources);

        for (int i = 1; i <= iters; i++) {
            Path classes = work.resolve("classes" + i);
            Path dexOut = work.resolve("dex" + i);
            Files.createDirectories(classes);
            Files.createDirectories(dexOut);

            long t0 = System.nanoTime();
            List<String> opts = List.of("-classpath", androidJar.toString(),
                    "-d", classes.toString(), "-proc:none");
            boolean ok = javac.getTask(null, fm, null, opts, null,
                    fm.getJavaFileObjectsFromFiles(sources)).call();
            long t1 = System.nanoTime();
            if (!ok) { System.out.println("javac FAILED"); return; }

            List<Path> classFiles = new ArrayList<>();
            try (var walk = Files.walk(classes)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(classFiles::add);
            }
            D8Command cmd = D8Command.builder()
                    .addProgramFiles(classFiles)
                    .addLibraryFiles(androidJar)
                    .setMinApiLevel(30)
                    .setOutput(dexOut, OutputMode.DexIndexed)
                    .build();
            D8.run(cmd);
            long t2 = System.nanoTime();

            System.out.println("iter " + i + ": javac=" + ((t1 - t0) / 1_000_000)
                    + "ms d8=" + ((t2 - t1) / 1_000_000) + "ms total="
                    + ((t2 - t0) / 1_000_000) + "ms");
        }
    }
}
