package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end on the host JVM: real BTA CompilationService, real kotlinc, real IC caches.
 * The incremental assertions pin the README gotchas - if the engine silently falls back
 * to a full compile (the failure mode the shrunk-snapshot path and SourcesChanges.Known
 * exist to prevent), these tests go red.
 */
class IncrementalCompilerTest {
	@TempDir
	lateinit var tempDir: File

	private lateinit var srcDir: File
	private lateinit var workDir: File

	@BeforeEach
	fun setUp() {
		srcDir = File(tempDir, "src").apply { mkdirs() }
		workDir = File(tempDir, "work").apply { mkdirs() }
	}

	private fun compiler() = IncrementalCompiler(listOf(TestSdk.kotlinStdlib()), workDir.toPath())

	private fun writeSource(
		name: String,
		content: String,
	): File = File(srcDir, name).apply { writeText(content) }

	private fun greeterKt(greeting: String = "Hello") =
		writeSource(
			"Greeter.kt",
			"""
			package demo

			class Greeter(private val name: String) {
				fun greet(): String = "$greeting, ${'$'}name!"
			}
			""".trimIndent(),
		)

	private fun mainKt() =
		writeSource(
			"Main.kt",
			"""
			package demo

			fun main() {
				println(Greeter("world").greet())
			}
			""".trimIndent(),
		)

	@Test
	fun `first build compiles all sources and seeds the IC caches`() {
		val sources = listOf(greeterKt(), mainKt())
		val compiler = compiler()

		val result = compiler.compile(sources, changedFiles = sources)

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (result as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/Greeter.class").isFile).isTrue()
		assertThat(File(classesDir, "demo/MainKt.class").isFile).isTrue()
		// The seed build must leave the shrunk snapshot at EXACTLY this path - a
		// mismatch means every later build silently degrades to non-incremental.
		assertThat(File(workDir, "shrunk-classpath-snapshot.bin").isFile).isTrue()
	}

	@Test
	fun `editing one file recompiles incrementally, not a full rebuild`() {
		val greeter = greeterKt()
		val sources = listOf(greeter, mainKt())
		val compiler = compiler()
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Success::class.java)

		greeterKt(greeting = "Howdy")
		val result = compiler.compile(sources, changedFiles = listOf(greeter))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val log = compiler.lastCompileLog.joinToString("\n")
		// The IC engine reports each compile iteration with the files it actually
		// recompiled: the changed file must be there, and no fallback marker may appear.
		assertThat(log).contains("Greeter.kt")
		assertThat(log).contains("compile iteration")
		assertThat(log).doesNotContainMatch("(?i)non-incremental")
		assertThat(log).doesNotContain("CLASSPATH_SNAPSHOT_NOT_FOUND")
		assertThat(log).doesNotContain("UNKNOWN_CHANGES_IN_GRADLE_INPUTS")
		val iterationLines = compiler.lastCompileLog.filter { it.contains("compile iteration") }
		assertThat(iterationLines).isNotEmpty()
		for (line in iterationLines) {
			assertThat(line).doesNotContain("Main.kt")
		}
	}

	@Test
	fun `changed class files list the seed build's outputs, then only the recompiled ones`() {
		val greeter = greeterKt()
		val sources = listOf(greeter, mainKt())
		val compiler = compiler()

		val first = compiler.compile(sources, changedFiles = sources)
		assertThat(first).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat((first as IncrementalCompiler.Result.Success).changedClassFiles)
			.containsAtLeast("demo/Greeter.class", "demo/MainKt.class")

		greeterKt(greeting = "Howdy")
		val second = compiler.compile(sources, changedFiles = listOf(greeter))

		assertThat(second).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val changed = (second as IncrementalCompiler.Result.Success).changedClassFiles
		// The recompiled file is reported; the untouched one is not - an over- or
		// under-report here would skew the CoGo-side restart decision.
		assertThat(changed).contains("demo/Greeter.class")
		assertThat(changed).doesNotContain("demo/MainKt.class")
	}

	@Test
	fun `syntax error yields structured diagnostics with file and line`() {
		val sources = listOf(greeterKt(), mainKt())
		val compiler = compiler()
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Success::class.java)

		val broken =
			writeSource(
				"Greeter.kt",
				"""
				package demo

				class Greeter(private val name: String) {
					fun greet(): String = "Hello, ${'$'}name!" +
				}
				""".trimIndent(),
			)
		val result = compiler.compile(sources, changedFiles = listOf(broken))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Failed::class.java)
		val diagnostics = (result as IncrementalCompiler.Result.Failed).diagnostics
		assertThat(diagnostics).isNotEmpty()
		val located = diagnostics.firstOrNull { it.file?.endsWith("Greeter.kt") == true }
		assertThat(located).isNotNull()
		assertThat(located!!.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(located.line).isAtLeast(1)
	}

	@Test
	fun `recovering from a syntax error compiles cleanly again`() {
		val greeter = greeterKt()
		val sources = listOf(greeter, mainKt())
		val compiler = compiler()
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		writeSource("Greeter.kt", "package demo\n\nclass Greeter(private val name: String) {\n")
		assertThat(compiler.compile(sources, changedFiles = listOf(greeter)))
			.isInstanceOf(IncrementalCompiler.Result.Failed::class.java)

		greeterKt(greeting = "Fixed")
		val result = compiler.compile(sources, changedFiles = listOf(greeter))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
	}

	@Test
	fun `java sources compile against kotlin output into the same classes dir`() {
		val javaSource =
			writeSource(
				"JavaUser.java",
				"""
				package demo;

				public class JavaUser {
					public String use() {
						return new Greeter("java").greet();
					}
				}
				""".trimIndent(),
			)
		val sources = listOf(greeterKt(), mainKt(), javaSource)
		val compiler = compiler()

		val result = compiler.compile(sources, changedFiles = sources)

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (result as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/JavaUser.class").isFile).isTrue()
		assertThat(File(classesDir, "demo/Greeter.class").isFile).isTrue()
	}

	private fun composeCompiler() =
		IncrementalCompiler(
			listOf(TestSdk.kotlinStdlib(), TestSdk.composeRuntimeJar()!!),
			workDir.toPath(),
			compilerPluginJars = listOf(TestSdk.composePluginJar()!!),
		)

	private fun composablesKt(marker: String = "MARKER_V1") =
		writeSource(
			"Composables.kt",
			"""
			package demo

			import androidx.compose.runtime.Composable
			import androidx.compose.runtime.getValue
			import androidx.compose.runtime.mutableStateOf
			import androidx.compose.runtime.remember
			import androidx.compose.runtime.setValue

			@Composable
			fun Greeting(name: String) {
				var count by remember { mutableStateOf(0) }
				Label("$marker hello, ${'$'}name (${'$'}count)")
				count += 1
			}

			@Composable
			fun Label(text: String) {
				Recorder.record(text)
			}
			""".trimIndent(),
		)

	private fun recorderKt() =
		writeSource(
			"Recorder.kt",
			"""
			package demo

			object Recorder {
				val seen = mutableListOf<String>()

				fun record(text: String) {
					seen += text
				}
			}
			""".trimIndent(),
		)

	@Test
	@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#composeToolchainAvailable")
	fun `compose plugin transforms composable functions`() {
		val sources = listOf(composablesKt(), recorderKt())
		val compiler = composeCompiler()

		val result = compiler.compile(sources, changedFiles = sources)

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (result as IncrementalCompiler.Result.Success).classesDir
		val composables = File(classesDir, "demo/ComposablesKt.class")
		assertThat(composables.isFile).isTrue()
		// The Compose transform rewrites @Composable functions to take a Composer
		// parameter; its type name in the constant pool is the proof the plugin ran
		// (without the plugin the same source compiles to a plain static method).
		assertThat(String(composables.readBytes(), Charsets.ISO_8859_1))
			.contains("androidx/compose/runtime/Composer")
	}

	@Test
	@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#composeToolchainAvailable")
	fun `composable edit recompiles incrementally with the plugin active`() {
		val composables = composablesKt()
		val sources = listOf(composables, recorderKt())
		val compiler = composeCompiler()
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Success::class.java)

		composablesKt(marker = "MARKER_V2")
		val result = compiler.compile(sources, changedFiles = listOf(composables))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (result as IncrementalCompiler.Result.Success).classesDir
		assertThat(String(File(classesDir, "demo/ComposablesKt.class").readBytes(), Charsets.ISO_8859_1))
			.contains("MARKER_V2")
		val log = compiler.lastCompileLog.joinToString("\n")
		assertThat(log).doesNotContainMatch("(?i)non-incremental")
		assertThat(log).doesNotContain("CLASSPATH_SNAPSHOT_NOT_FOUND")
		assertThat(log).doesNotContain("UNKNOWN_CHANGES_IN_GRADLE_INPUTS")
		val iterationLines = compiler.lastCompileLog.filter { it.contains("compile iteration") }
		assertThat(iterationLines).isNotEmpty()
		for (line in iterationLines) {
			assertThat(line).doesNotContain("Recorder.kt")
		}
	}

	@Test
	fun `kotlin source resolves a same-module java class it calls (D2 direction a)`() {
		// Without javaSources in compileJvm's source list, kotlinc has zero visibility into
		// a sibling .java file that isn't precompiled onto the classpath yet - this used to
		// fail baseline compile outright with "Unresolved reference".
		val javaSource =
			writeSource(
				"JavaCalculator.java",
				"""
				package demo;

				public class JavaCalculator {
					public int computeTotal(int a, int b) { return a + b; }
				}
				""".trimIndent(),
			)
		val callerSource =
			writeSource(
				"OrderService.kt",
				"""
				package demo

				class OrderService {
					fun total(a: Int, b: Int) = JavaCalculator().computeTotal(a, b)
				}
				""".trimIndent(),
			)
		val sources = listOf(javaSource, callerSource)
		val compiler = compiler()

		val result = compiler.compile(sources, changedFiles = sources)

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (result as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/JavaCalculator.class").isFile).isTrue()
		assertThat(File(classesDir, "demo/OrderService.class").isFile).isTrue()
	}

	@Test
	fun `a java-only signature change recompiles its unedited kotlin caller`() {
		// The regression this guards: SourcesChanges.Known filtered out .java entries, so a
		// changedFiles list containing ONLY a .java path told the incremental engine "nothing
		// kotlin changed" and it skipped OrderService.kt entirely - leaving its .class calling
		// the OLD Java descriptor even after JavaCalculator's signature changed underneath it.
		// That's exactly the under-recompilation bug the ADFA-4128 D2 corpus entry targets.
		val javaSource =
			writeSource(
				"JavaCalculator.java",
				"""
				package demo;

				public class JavaCalculator {
					public int computeTotal(int a, int b) { return a + b; }
				}
				""".trimIndent(),
			)
		val callerSource =
			writeSource(
				"OrderService.kt",
				"""
				package demo

				class OrderService {
					fun total(a: Int, b: Int) = JavaCalculator().computeTotal(a, b)
				}
				""".trimIndent(),
			)
		val sources = listOf(javaSource, callerSource)
		val compiler = compiler()
		val baseline = compiler.compile(sources, changedFiles = sources)
		assertThat(baseline).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (baseline as IncrementalCompiler.Result.Success).classesDir
		val before = File(classesDir, "demo/OrderService.class").readBytes()

		// Widen the return type: OrderService's call-site descriptor must change to match, even
		// though OrderService.kt itself is untouched on disk and NOT in changedFiles.
		writeSource(
			"JavaCalculator.java",
			"""
			package demo;

			public class JavaCalculator {
				public long computeTotal(int a, int b) { return (long) a + b; }
			}
			""".trimIndent(),
		)
		val result = compiler.compile(sources, changedFiles = listOf(javaSource))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val after = File(classesDir, "demo/OrderService.class").readBytes()
		assertThat(after).isNotEqualTo(before)
	}

	/**
	 * A genuine Kotlin<->Java cycle: mutual calls, plus a Java class whose supertype is a
	 * Kotlin source in the same compile. Neither language can be compiled first in
	 * isolation, so this is the shape the corpus's `mixed-lang-cyclic` app pins end to end.
	 */
	private fun cyclicSources(rendererBody: String = """return "Node(" + node.getLabel() + ")";"""): List<File> {
		val node =
			writeSource(
				"TreeNode.kt",
				"""
				package demo

				open class TreeNode(val label: String) {
					open fun describe() = NodeRenderer.render(this)

					companion object {
						fun leaf(label: String): TreeNode = JavaLeafNode(label)
					}
				}
				""".trimIndent(),
			)
		val renderer =
			writeSource(
				"NodeRenderer.java",
				"""
				package demo;

				public final class NodeRenderer {
					public static String render(TreeNode node) { $rendererBody }
				}
				""".trimIndent(),
			)
		val leaf =
			writeSource(
				"JavaLeafNode.java",
				"""
				package demo;

				public class JavaLeafNode extends TreeNode {
					public JavaLeafNode(String label) { super(label); }

					@Override
					public String describe() { return "Leaf[" + getLabel() + "]"; }
				}
				""".trimIndent(),
			)
		return listOf(node, renderer, leaf)
	}

	@Test
	fun `mutually referencing kotlin and java sources compile in one pass`() {
		val sources = cyclicSources()
		val compiler = compiler()

		val result = compiler.compile(sources, changedFiles = sources)

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (result as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/TreeNode.class").isFile).isTrue()
		assertThat(File(classesDir, "demo/NodeRenderer.class").isFile).isTrue()
		// The Java subclass is the sharp end: javac could only resolve its supertype
		// because kotlinc had already emitted TreeNode into the same output dir.
		assertThat(File(classesDir, "demo/JavaLeafNode.class").isFile).isTrue()
	}

	@Test
	fun `a java body-only edit leaves kotlin untouched`() {
		val sources = cyclicSources()
		val compiler = compiler()
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Success::class.java)

		cyclicSources(rendererBody = """return "Node[" + node.getLabel() + "]";""")
		val result = compiler.compile(sources, changedFiles = listOf(sources[1]))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		// No Java signature moved, so no Kotlin class can differ - and none may be rewritten.
		assertThat(compiler.lastJavaAbiChange).isEmpty()
		val changed = (result as IncrementalCompiler.Result.Success).changedClassFiles
		assertThat(changed).contains("demo/NodeRenderer.class")
		assertThat(changed).doesNotContain("demo/TreeNode.class")
	}

	private fun limitsSources(max: String): List<File> {
		val limits =
			writeSource(
				"JavaLimits.java",
				"""
				package demo;

				public class JavaLimits {
					public static final int MAX = $max;
				}
				""".trimIndent(),
			)
		val caller =
			writeSource(
				"LimitUser.kt",
				"""
				package demo

				class LimitUser {
					fun ceiling(): Int = JavaLimits.MAX
				}
				""".trimIndent(),
			)
		return listOf(limits, caller)
	}

	@Test
	fun `a java constant's new value reaches its kotlin caller's bytecode`() {
		// Kotlin inlines Java compile-time constants, so nothing about this edit shows up in
		// a signature - if the ABI fingerprint ignored constant VALUES, the fast path would
		// skip LimitUser and leave it returning 5 forever.
		val sources = limitsSources("5")
		val compiler = compiler()
		val baseline = compiler.compile(sources, changedFiles = sources)
		assertThat(baseline).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val classesDir = (baseline as IncrementalCompiler.Result.Success).classesDir
		val before = File(classesDir, "demo/LimitUser.class").readBytes()

		limitsSources("7")
		val result = compiler.compile(sources, changedFiles = listOf(sources[0]))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(compiler.lastJavaAbiChange).contains("JavaLimits")
		assertThat(File(classesDir, "demo/LimitUser.class").readBytes()).isNotEqualTo(before)
	}

	@Test
	fun `a failed compile does not become the java ABI baseline`() {
		// Otherwise the next compile compares against an ABI whose bytecode was never
		// emitted, and silently skips the Kotlin recompile the Java change still needs.
		val sources = limitsSources("5")
		val compiler = compiler()
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Success::class.java)

		limitsSources("7")
		writeSource("LimitUser.kt", "package demo\n\nclass LimitUser { fun ceiling(): Int = ")
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Failed::class.java)

		// Repair only the Kotlin file; the Java constant is still 7, still unaccounted for.
		writeSource(
			"LimitUser.kt",
			"""
			package demo

			class LimitUser {
				fun ceiling(): Int = JavaLimits.MAX
			}
			""".trimIndent(),
		)
		val result = compiler.compile(sources, changedFiles = listOf(sources[1]))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(compiler.lastJavaAbiChange).contains("JavaLimits")
	}

	@Test
	fun `java error yields structured diagnostics and fails the compile`() {
		val javaSource =
			writeSource(
				"Broken.java",
				"""
				package demo;

				public class Broken {
					public int broken() { return "not an int"; }
				}
				""".trimIndent(),
			)
		val sources = listOf(greeterKt(), javaSource)
		val compiler = compiler()

		val result = compiler.compile(sources, changedFiles = sources)

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Failed::class.java)
		val diagnostics = (result as IncrementalCompiler.Result.Failed).diagnostics
		val located = diagnostics.firstOrNull { it.file?.endsWith("Broken.java") == true }
		assertThat(located).isNotNull()
		assertThat(located!!.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(located.line).isEqualTo(4)
	}
}
