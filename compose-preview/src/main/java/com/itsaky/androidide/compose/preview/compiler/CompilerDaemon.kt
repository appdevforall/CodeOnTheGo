package com.itsaky.androidide.compose.preview.compiler

import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

class CompilerDaemon(
    private val classpathManager: ComposeClasspathManager,
    private val workDir: File
) {
    private var daemonProcess: Process? = null
    private var processWriter: OutputStreamWriter? = null
    private var processReader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private val mutex = Mutex()

    private var idleTimeoutJob: Job? = null
    private val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isStartingUp = false

    private val wrapperDir = File(workDir, "daemon").apply { mkdirs() }
    private val wrapperClass = File(wrapperDir, "CompilerWrapper.class")

    suspend fun compile(
        sourceFiles: List<File>,
        outputDir: File,
        classpath: String,
        composePlugin: File
    ): CompilerResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureDaemonRunning()

            val args = buildCompilerArgs(sourceFiles, outputDir, classpath, composePlugin)
            val argsLine = "COMPILE\u0000" + args.joinToString("\u0000") + "\n"

            try {
                processWriter?.write(argsLine)
                processWriter?.flush()

                val result = readDaemonResponse()

                if (result == null) {
                    LOG.error("Daemon compilation timed out after {}ms", COMPILE_TIMEOUT_MS)
                    stopDaemon()
                    return@withContext CompilerResult(
                        success = false,
                        output = "",
                        errorOutput = "Compilation timed out after ${COMPILE_TIMEOUT_MS / 1000} seconds"
                    )
                }

                val (output, errors) = result
                scheduleIdleTimeout()

                val hasErrors = output.contains("error:") || errors.contains("error:")

                CompilerResult(
                    success = !hasErrors && outputDir.walkTopDown().any { it.extension == "class" },
                    output = output,
                    errorOutput = errors
                )
            } catch (e: Exception) {
                LOG.error("Daemon compilation failed", e)
                stopDaemon()
                CompilerResult(success = false, output = "", errorOutput = e.message ?: "Unknown error")
            }
        }
    }

    suspend fun dex(
        classesDir: File,
        outputDir: File
    ): DexResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureDaemonRunning()

            outputDir.mkdirs()

            val classFiles = classesDir.walkTopDown()
                .filter { it.extension == "class" }
                .toList()

            if (classFiles.isEmpty()) {
                return@withContext DexResult(
                    success = false,
                    dexFile = null,
                    errorOutput = "No .class files found in $classesDir"
                )
            }

            val d8Args = buildD8Args(classFiles, outputDir)
            val argsLine = "DEX\u0000" + d8Args.joinToString("\u0000") + "\n"

            try {
                processWriter?.write(argsLine)
                processWriter?.flush()

                val result = readDaemonResponse()

                if (result == null) {
                    LOG.error("Daemon D8 timed out after {}ms", COMPILE_TIMEOUT_MS)
                    stopDaemon()
                    return@withContext DexResult(
                        success = false,
                        dexFile = null,
                        errorOutput = "D8 timed out"
                    )
                }

                val (output, errors) = result
                scheduleIdleTimeout()

                val dexFile = File(outputDir, "classes.dex")
                val success = output.contains("DEX_SUCCESS") && dexFile.exists()

                if (!success) {
                    LOG.error("Daemon D8 failed: {} {}", output, errors)
                }

                DexResult(
                    success = success,
                    dexFile = if (success) dexFile else null,
                    errorOutput = if (!success) (errors.ifEmpty { output }) else ""
                )
            } catch (e: Exception) {
                LOG.error("Daemon D8 failed", e)
                stopDaemon()
                DexResult(success = false, dexFile = null, errorOutput = e.message ?: "Unknown error")
            }
        }
    }

    private fun buildD8Args(classFiles: List<File>, outputDir: File): List<String> = buildList {
        add("--release")
        add("--min-api")
        add("21")

        classpathManager.getRuntimeJars()
            .filter { it.exists() }
            .forEach { jar ->
                add("--classpath")
                add(jar.absolutePath)
            }

        if (Environment.ANDROID_JAR.exists()) {
            add("--lib")
            add(Environment.ANDROID_JAR.absolutePath)
        }

        add("--output")
        add(outputDir.absolutePath)

        classFiles.forEach { add(it.absolutePath) }
    }

    private suspend fun readDaemonResponse(): Pair<String, String>? {
        return withTimeoutOrNull(COMPILE_TIMEOUT_MS) {
            val response = StringBuilder()
            var line: String?

            while (true) {
                line = processReader?.readLine()
                if (line == null || line == "---END---") break
                response.appendLine(line)
            }

            val errorOutput = StringBuilder()
            while (errorReader?.ready() == true) {
                errorOutput.appendLine(errorReader?.readLine())
            }

            Pair(response.toString(), errorOutput.toString())
        }
    }

    private fun ensureDaemonRunning() {
        if (daemonProcess?.isAlive == true) {
            return
        }

        ensureWrapperCompiled()
        startDaemon()
    }

    private fun ensureWrapperCompiled() {
        val versionFile = File(wrapperDir, ".wrapper_version")
        val storedVersion = if (versionFile.exists()) versionFile.readText().trim().toIntOrNull() ?: 0 else 0

        if (wrapperClass.exists() && storedVersion == WRAPPER_VERSION) {
            return
        }

        wrapperClass.delete()

        LOG.info("Compiling daemon wrapper (v{})...", WRAPPER_VERSION)

        val wrapperSource = File(wrapperDir, "CompilerWrapper.java")
        wrapperSource.writeText(WRAPPER_SOURCE)

        val javac = File(Environment.JAVA.parentFile, "javac")
        val kotlinCompilerJar = classpathManager.getKotlinCompiler()
            ?: throw RuntimeException("Kotlin compiler not found in local Maven repository. Build any project first.")

        val command = listOf(
            javac.absolutePath,
            "-cp",
            kotlinCompilerJar.absolutePath,
            "-d",
            wrapperDir.absolutePath,
            wrapperSource.absolutePath
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            LOG.error("Failed to compile wrapper: {}", output)
            throw RuntimeException("Failed to compile daemon wrapper: $output")
        }

        wrapperSource.delete()
        versionFile.writeText(WRAPPER_VERSION.toString())
        LOG.info("Daemon wrapper compiled successfully")
    }

    private fun startDaemon() {
        val javaExecutable = Environment.JAVA

        val d8JarPath = classpathManager.getD8Jar()?.absolutePath ?: ""
        val bootstrapClasspath = classpathManager.getCompilerBootstrapClasspath() +
                File.pathSeparator + wrapperDir.absolutePath +
                (if (d8JarPath.isNotEmpty()) File.pathSeparator + d8JarPath else "")

        val command = listOf(
            javaExecutable.absolutePath,
            "-Xmx512m",
            "-cp",
            bootstrapClasspath,
            "CompilerWrapper"
        )

        LOG.info("Starting compiler daemon...")

        val processBuilder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(false)

        daemonProcess = processBuilder.start()
        processWriter = OutputStreamWriter(daemonProcess!!.outputStream)
        processReader = BufferedReader(InputStreamReader(daemonProcess!!.inputStream))
        errorReader = BufferedReader(InputStreamReader(daemonProcess!!.errorStream))

        val ready = processReader?.readLine()
        if (ready == "READY") {
            LOG.info("Compiler daemon started and ready")
            scheduleIdleTimeout()
        } else {
            LOG.error("Daemon failed to start, got: {}", ready)
            stopDaemon()
            throw RuntimeException("Daemon failed to start")
        }
    }

    private fun scheduleIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = timeoutScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (daemonProcess?.isAlive == true) {
                LOG.info("Stopping idle compiler daemon after {}ms", IDLE_TIMEOUT_MS)
                stopDaemon()
            }
        }
    }

    fun stopDaemon() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null

        try {
            processWriter?.write("EXIT\n")
            processWriter?.flush()
            daemonProcess?.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            LOG.debug("Error sending EXIT to daemon", e)
        }

        try {
            processWriter?.close()
            processReader?.close()
            errorReader?.close()
            daemonProcess?.destroyForcibly()
        } catch (e: Exception) {
            LOG.warn("Error stopping daemon", e)
        } finally {
            daemonProcess = null
            processWriter = null
            processReader = null
            errorReader = null
        }
    }

    fun shutdown() {
        stopDaemon()
        timeoutScope.cancel()
    }

    suspend fun startEagerly() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (daemonProcess?.isAlive == true) return@withContext
            isStartingUp = true
            try {
                ensureDaemonRunning()
            } finally {
                isStartingUp = false
            }
        }
    }

    data class CompilerResult(
        val success: Boolean,
        val output: String,
        val errorOutput: String
    )

    data class DexResult(
        val success: Boolean,
        val dexFile: File?,
        val errorOutput: String = ""
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(CompilerDaemon::class.java)

        private const val IDLE_TIMEOUT_MS = 120_000L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val COMPILE_TIMEOUT_MS = 300_000L
        private const val WRAPPER_VERSION = 2

        private val WRAPPER_SOURCE = """
            import java.io.*;
            import java.lang.reflect.*;
            import java.util.Arrays;

            public class CompilerWrapper {
                private static Object kotlinCompiler;
                private static Method kotlinExecMethod;
                private static Method d8ParseMethod;
                private static Method d8RunMethod;
                private static Class<?> d8CommandClass;

                public static void main(String[] args) throws Exception {
                    Class<?> compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
                    kotlinCompiler = compilerClass.getDeclaredConstructor().newInstance();
                    kotlinExecMethod = compilerClass.getMethod("exec", PrintStream.class, String[].class);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    System.out.println("READY");
                    System.out.flush();

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("EXIT")) {
                            break;
                        }

                        String[] parts = line.split("\u0000");
                        String command = parts[0];

                        try {
                            if (command.equals("DEX")) {
                                String[] d8Args = Arrays.copyOfRange(parts, 1, parts.length);
                                handleDex(d8Args);
                            } else if (command.equals("COMPILE")) {
                                String[] compilerArgs = Arrays.copyOfRange(parts, 1, parts.length);
                                handleCompile(compilerArgs);
                            } else {
                                handleCompile(parts);
                            }
                        } catch (Exception e) {
                            System.out.println("ERROR:" + e.getMessage());
                            e.printStackTrace(System.out);
                        }

                        System.out.println("---END---");
                        System.out.flush();
                    }
                }

                private static void handleCompile(String[] compilerArgs) throws Exception {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    PrintStream ps = new PrintStream(baos);
                    Object result = kotlinExecMethod.invoke(kotlinCompiler, ps, compilerArgs);
                    ps.flush();
                    String output = baos.toString();
                    if (!output.isEmpty()) {
                        System.out.print(output);
                    }
                    System.out.println("EXIT_CODE:" + result);
                }

                private static void handleDex(String[] d8Args) throws Exception {
                    if (d8CommandClass == null) {
                        d8CommandClass = Class.forName("com.android.tools.r8.D8Command");
                        d8ParseMethod = d8CommandClass.getMethod("parse", String[].class);
                        Class<?> d8Class = Class.forName("com.android.tools.r8.D8");
                        d8RunMethod = d8Class.getMethod("run", d8CommandClass);
                    }

                    Object cmd = d8ParseMethod.invoke(null, (Object) d8Args);
                    d8RunMethod.invoke(null, cmd);
                    System.out.println("DEX_SUCCESS");
                }
            }
        """.trimIndent()
    }
}
