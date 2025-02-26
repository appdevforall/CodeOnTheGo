package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.lsp.java.compiler.CompilerProvider
import com.itsaky.androidide.lsp.java.compiler.SynchronizedTask
import com.itsaky.androidide.lsp.java.models.CompilationRequest
import com.itsaky.androidide.lsp.java.parser.ParseTask
import com.itsaky.androidide.projects.api.ModuleProject
import jdkx.tools.JavaFileObject
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.nio.file.Path
import java.util.Optional
import java.util.TreeSet

class KotlinCompilerService(val module: ModuleProject?) : CompilerProvider {

    companion object {
        val log = org.slf4j.LoggerFactory.getLogger(KotlinCompilerService::class.java)
        val NO_MODULE_COMPILER = KotlinCompilerService(null)
    }

    override fun getModuleInstance(): ModuleProject {
        return module!!
    }

    override fun publicTopLevelTypes(): TreeSet<String> = TreeSet()

    override fun packagePrivateTopLevelTypes(packageName: String): TreeSet<String> = TreeSet()

    override fun findAnywhere(className: String): Optional<JavaFileObject> = Optional.empty()

    override fun findTypeDeclaration(className: String): Path = CompilerProvider.NOT_FOUND

    override fun findTypeReferences(className: String): Array<Path> = arrayOf()

    override fun findMemberReferences(className: String, memberName: String): Array<Path> =
        arrayOf()

    override fun findQualifiedNames(simpleName: String, onlyOne: Boolean): List<String> = listOf()

    override fun parse(file: Path): ParseTask {
        log.info("Parsing Kotlin file: $file")
        return ParseTask(null, null)
    }

    override fun parse(file: JavaFileObject?): ParseTask = ParseTask(null, null)

    override fun compile(request: CompilationRequest): SynchronizedTask {
        // TODO: Implement kotlin compiler
//        var compileTask: KotlinCompileTask? = null
//        try {
//            val configuration = CompilerConfiguration()
//
//            for (source in request.sources) {
//                configuration.addKotlinSourceRoot(source.toString())
//            }
//
//            val disposable = Disposer.newDisposable()
//            val environment = KotlinCoreEnvironment.createForProduction(
//                disposable,
//                configuration,
//                EnvironmentConfigFiles.JVM_CONFIG_FILES
//            )
//
//            val exitCode: ExitCode = K2JVMCompiler().exec(errStream = System.err, args = emptyArray())
//            if (exitCode != ExitCode.OK) {
//                log.error("Kotlin compilation failed with exit code: ${exitCode.code}")
//            } else {
//                log.info("Kotlin compilation succeeded.")
//            }
//            // Create a KotlinCompileTask to wrap the result (diagnostics left empty for now)
//            compileTask = KotlinCompileTask(configuration, exitCode, emptyList())
//            // Dispose the disposable after compilation
//            Disposer.dispose(disposable)
//        } catch (e: Exception) {
//            log.error("Exception during Kotlin compilation", e)
//        }
//        // Create a SynchronizedTask and set our compile task into it.
//        val synchronizedTask = SynchronizedTask()
//        compileTask?.let { synchronizedTask.setTask(it) }
//        return synchronizedTask
        return SynchronizedTask()
    }

    fun destroy() {}
}
