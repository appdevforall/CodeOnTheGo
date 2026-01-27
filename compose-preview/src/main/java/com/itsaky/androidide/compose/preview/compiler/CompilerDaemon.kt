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
            val argsLine = args.joinToString("\u0000") + "\n"

            try {
                processWriter?.write(argsLine)
                processWriter?.flush()

                val result = withTimeoutOrNull(COMPILE_TIMEOUT_MS) {
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

                    val output = response.toString()
                    val errors = errorOutput.toString()
                    Pair(output, errors)
                }

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

    private fun ensureDaemonRunning() {
        if (daemonProcess?.isAlive == true) {
            return
        }

        ensureWrapperCompiled()
        startDaemon()
    }

    private fun ensureWrapperCompiled() {
        if (wrapperClass.exists()) {
            return
        }

        LOG.info("Compiling daemon wrapper...")

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
        LOG.info("Daemon wrapper compiled successfully")
    }

    private fun startDaemon() {
        val javaExecutable = Environment.JAVA
        val bootstrapClasspath = classpathManager.getCompilerBootstrapClasspath() +
                File.pathSeparator + wrapperDir.absolutePath

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

    private fun buildCompilerArgs(
        sourceFiles: List<File>,
        outputDir: File,
        classpath: String,
        composePlugin: File
    ): List<String> = buildList {
        if (composePlugin.exists()) {
            add("-Xplugin=${composePlugin.absolutePath}")
        }
        add("-classpath")
        add(classpath)
        add("-d")
        add(outputDir.absolutePath)
        add("-jvm-target")
        add("1.8")
        add("-no-stdlib")
        add("-Xskip-metadata-version-check")
        sourceFiles.forEach { add(it.absolutePath) }
    }

    fun stopDaemon() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
        timeoutScope.cancel()

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

    data class CompilerResult(
        val success: Boolean,
        val output: String,
        val errorOutput: String
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(CompilerDaemon::class.java)

        private const val IDLE_TIMEOUT_MS = 120_000L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val COMPILE_TIMEOUT_MS = 300_000L

        private val WRAPPER_SOURCE = """
            import java.io.*;
            import java.lang.reflect.*;

            public class CompilerWrapper {
                public static void main(String[] args) throws Exception {
                    Class<?> compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
                    Object compiler = compilerClass.getDeclaredConstructor().newInstance();
                    Method execMethod = compilerClass.getMethod("exec", PrintStream.class, String[].class);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    System.out.println("READY");
                    System.out.flush();

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("EXIT")) {
                            break;
                        }

                        String[] compilerArgs = line.split("\u0000");

                        try {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            PrintStream ps = new PrintStream(baos);
                            Object result = execMethod.invoke(compiler, ps, compilerArgs);
                            ps.flush();
                            String output = baos.toString();
                            if (!output.isEmpty()) {
                                System.out.print(output);
                            }
                            System.out.println("EXIT_CODE:" + result);
                        } catch (Exception e) {
                            System.out.println("ERROR:" + e.getMessage());
                            e.printStackTrace(System.out);
                        }

                        System.out.println("---END---");
                        System.out.flush();
                    }
                }
            }
        """.trimIndent()
    }
}
