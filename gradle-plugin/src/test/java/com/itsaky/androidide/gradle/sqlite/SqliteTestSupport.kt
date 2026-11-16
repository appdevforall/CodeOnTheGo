package com.itsaky.androidide.gradle.sqlite

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/**
 * Prints one line naming what the environment lacks. Then skips the test locally, where not everyone has
 * every jar, but fails it when the environment variable CI is set: a CI run that skips these tests
 * looks green while checking nothing.
 */
internal fun requireEnvironment(
	present: Boolean,
	missing: String,
) {
	if (present) return
	val onCi = !System.getenv("CI").isNullOrEmpty()
	System.err.println("SQLITE REWRITE TESTS: missing $missing (${if (onCi) "failing, CI is set" else "skipping, set CI to fail instead"})")
	if (onCi) throw AssertionError("The environment lacks $missing, and CI is set, so the SQLite rewrite tests do not skip")
	assumeTrue(false, missing)
}

/** Jars the tests compile and resolve against. Each lookup skips the test with a message, or fails it on CI. */
internal object TestJars {
	private val home = File(System.getProperty("user.home"))

	val androidJar: File by lazy {
		val sdkRoots =
			listOfNotNull(
				System.getenv("ANDROID_HOME"),
				System.getenv("ANDROID_SDK_ROOT"),
				generateSequence(File("").absoluteFile) { it.parentFile }
					.map { File(it, "local.properties") }
					.firstOrNull { it.isFile }
					?.readLines()
					?.firstOrNull { it.startsWith("sdk.dir=") }
					?.substringAfter("="),
				File(home, "Android/Sdk").path,
			)
		val jar =
			sdkRoots
				.flatMap { root -> listOf(36, 35, 34, 33).map { File(root, "platforms/android-$it/android.jar") } }
				.firstOrNull { it.isFile }
		requireEnvironment(jar != null, "No android.jar found: set ANDROID_HOME or sdk.dir in local.properties")
		jar!!
	}

	private fun gradleCacheAar(
		group: String,
		artifact: String,
		version: String,
		file: String,
	): File? =
		File(home, ".gradle/caches/modules-2/files-2.1/$group/$artifact/$version")
			.walkTopDown()
			.firstOrNull { it.name == file }

	private fun classesJarOf(
		aar: File,
		name: String,
	): File {
		val out = Files.createTempDirectory("sqlite-rewrite-test").toFile().also { it.deleteOnExit() }
		val jar = File(out, "$name.jar")
		ZipFile(aar).use { zip ->
			zip.getInputStream(zip.getEntry("classes.jar")).use { input -> jar.outputStream().use { input.copyTo(it) } }
		}
		return jar
	}

	val requeryJar: File by lazy {
		val aar =
			System.getenv("REQUERY_AAR")?.let(::File)?.takeIf { it.isFile }
				?: gradleCacheAar("com.github.requery", "sqlite-android", "3.49.0", "sqlite-android-3.49.0.aar")
		requireEnvironment(aar != null, "requery 3.49.0 AAR not found: set REQUERY_AAR or resolve it into the Gradle cache")
		classesJarOf(aar!!, "requery")
	}

	val androidxSqliteJar: File by lazy {
		val aar = gradleCacheAar("androidx.sqlite", "sqlite-android", "2.6.2", "sqlite.aar")
		requireEnvironment(aar != null, "androidx.sqlite 2.6.2 AAR not found in the Gradle cache")
		classesJarOf(aar!!, "androidx-sqlite")
	}

	val androidxSqliteFrameworkJar: File by lazy {
		val aar = gradleCacheAar("androidx.sqlite", "sqlite-framework-android", "2.6.2", "sqlite-framework.aar")
		requireEnvironment(aar != null, "androidx.sqlite framework 2.6.2 AAR not found in the Gradle cache")
		classesJarOf(aar!!, "androidx-sqlite-framework")
	}

	/** The Kotlin classes the framework classes call; null when the Gradle cache has none. */
	val kotlinStdlibJar: File? by lazy {
		File(home, ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib")
			.walkTopDown()
			.filter { it.name.startsWith("kotlin-stdlib-") && it.name.endsWith(".jar") && "sources" !in it.name }
			.maxByOrNull { it.name }
	}

	val androidxCoreJar: File by lazy {
		val aar = gradleCacheAar("androidx.core", "core", "1.13.1", "core-1.13.1.aar")
		requireEnvironment(aar != null, "androidx.core 1.13.1 AAR not found in the Gradle cache")
		classesJarOf(aar!!, "androidx-core")
	}
}

internal class JavaSource(
	val className: String,
	val code: String,
) : SimpleJavaFileObject(URI.create("string:///${className.replace('.', '/')}.java"), JavaFileObject.Kind.SOURCE) {
	override fun getCharContent(ignoreEncodingErrors: Boolean) = code
}

internal object Compiler {
	/** Class bytes by internal name, compiled with debug info (line numbers) against [classpath]. */
	fun compile(
		sources: List<JavaSource>,
		classpath: List<File> = listOf(TestJars.androidJar),
	): Map<String, ByteArray> {
		val compiler = ToolProvider.getSystemJavaCompiler() ?: error("No system Java compiler: run the tests on a JDK")
		val out = Files.createTempDirectory("sqlite-rewrite-classes").toFile().also { it.deleteOnExit() }
		val diagnostics = StringBuilder()
		val task =
			compiler.getTask(
				null,
				null,
				{ diagnostics.appendLine(it.toString()) },
				listOf(
					"-g",
					"-proc:none",
					"-source",
					"8",
					"-target",
					"8",
					"-Xlint:-options",
					"-d",
					out.path,
					"-classpath",
					classpath.joinToString(File.pathSeparator),
				),
				null,
				sources,
			)
		check(task.call()) { "Sample failed to compile:\n$diagnostics" }
		return out
			.walkTopDown()
			.filter { it.extension == "class" }
			.associate { it.relativeTo(out).path.removeSuffix(".class") to it.readBytes() }
	}
}

/** Class members of jars, for resolving a reference the way the JVM does. */
internal class ClassIndex(
	jars: List<File>,
) {
	private val classes = HashMap<String, ClassNode>()

	init {
		for (jar in jars) {
			ZipFile(jar).use { zip ->
				for (entry in zip.entries()) {
					if (entry.name.endsWith(".class")) {
						classes[entry.name.removeSuffix(".class")] =
							ClassNode().also { ClassReader(zip.getInputStream(entry)).accept(it, ClassReader.SKIP_CODE) }
					}
				}
			}
		}
	}

	fun addClass(bytes: ByteArray) {
		val n = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_CODE) }
		classes[n.name] = n
	}

	private fun load(name: String): ClassNode? =
		classes[name]
			?: ClassLoader.getSystemResourceAsStream("$name.class")?.use { s ->
				ClassNode().also { ClassReader(s).accept(it, ClassReader.SKIP_CODE) }
			}

	fun knows(name: String) = load(name) != null

	fun superClass(name: String): String? = load(name)?.superName

	fun superTypes(name: String): List<String> {
		val result = mutableListOf<String>()
		val pending = ArrayDeque(listOf(name))
		while (pending.isNotEmpty()) {
			val n = pending.removeFirst()
			val c = load(n) ?: continue
			if (n != name) result += n
			c.superName?.let(pending::add)
			pending.addAll(c.interfaces)
		}
		return result
	}

	private fun packageOf(name: String) = name.substringBeforeLast('/', "")

	private fun accessible(
		access: Int,
		declaring: String,
		caller: String?,
		protectedOk: Boolean,
	): Boolean =
		when {
			access and Opcodes.ACC_PUBLIC != 0 -> {
				true
			}

			access and Opcodes.ACC_PRIVATE != 0 -> {
				caller == declaring
			}

			access and Opcodes.ACC_PROTECTED != 0 -> {
				protectedOk || (
					caller != null && (
						caller == declaring || declaring in
							superTypes(
								caller,
							) || packageOf(caller) == packageOf(declaring)
					)
				)
			}

			else -> {
				caller != null && packageOf(caller) == packageOf(declaring)
			}
		}

	/**
	 * The class declaring the member, or null when the JVM would not link the reference: the member is
	 * missing, static where the call is not (or the reverse), or not accessible from [caller], a class
	 * in [classes] (null: an unrelated class in another package). [protectedOk] accepts protected members
	 * for a caller that is not a subclass, as a probe that stands in for one.
	 */
	fun resolve(
		owner: String,
		name: String,
		desc: String,
		field: Boolean,
		isStatic: Boolean? = null,
		caller: String? = null,
		protectedOk: Boolean = false,
	): String? {
		val ownerNode = load(owner) ?: return null
		if (!accessible(ownerNode.access, owner, caller, false)) return null
		val pending = ArrayDeque(listOf(owner))
		val seen = HashSet<String>()
		while (pending.isNotEmpty()) {
			val n = pending.removeFirst()
			if (!seen.add(n)) continue
			val c = load(n) ?: continue
			val access =
				if (field) {
					c.fields.firstOrNull { it.name == name && it.desc == desc }?.access
				} else {
					c.methods.firstOrNull { it.name == name && it.desc == desc }?.access
				}
			if (access != null) {
				if (isStatic != null && (access and Opcodes.ACC_STATIC != 0) != isStatic) return null
				return if (accessible(access, n, caller, protectedOk)) n else null
			}
			c.superName?.let(pending::add)
			pending.addAll(c.interfaces)
		}
		return null
	}

	fun isInterface(name: String) = load(name)?.let { it.access and Opcodes.ACC_INTERFACE != 0 } ?: false
}
