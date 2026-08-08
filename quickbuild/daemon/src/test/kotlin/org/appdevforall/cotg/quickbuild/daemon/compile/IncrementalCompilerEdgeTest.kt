package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.daemon.protocol.ProtocolCodec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Edges around IncrementalCompilerTest's happy paths: language-subset source sets, the
 * conservative fallback when the Java ABI cannot be known, and the removed-Java output
 * cleanup's path mapping (nested classes, unusual source roots, unrelated paths).
 */
class IncrementalCompilerEdgeTest {
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

	private fun writeJava(
		relativePath: String,
		content: String,
	): File =
		File(srcDir, relativePath).apply {
			parentFile!!.mkdirs()
			writeText(content)
		}

	private fun widgetJava(relativePath: String = "main/java/demo/Widget.java"): File =
		writeJava(relativePath, "package demo;\n\npublic class Widget { public int v() { return 1; } }")

	private fun kotlinSource(): File =
		File(srcDir, "Greeter.kt").apply {
			writeText("package demo\n\nclass Greeter { fun hi() = \"hi\" }\n")
		}

	@Test
	fun `a java-only source set compiles through javac alone`() {
		val widget = widgetJava()
		val compiler = compiler()

		val result = compiler.compile(listOf(widget), changedFiles = listOf(widget))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val success = result as IncrementalCompiler.Result.Success
		assertThat(File(success.classesDir, "demo/Widget.class").isFile).isTrue()
		assertThat(success.stats.javaSources).isEqualTo(1)
		// No Kotlin sources: nothing for kotlinc to do, and the stat must say so.
		assertThat(success.stats.kotlinToCompile).isEqualTo(0)
	}

	@Test
	fun `a java source that disappears from disk fails the compile, not the daemon`() {
		val widget = widgetJava()
		val kotlin = kotlinSource()
		val compiler = compiler()
		val sources = listOf(kotlin, widget)
		val first = compiler.compile(sources, changedFiles = sources)
		check(first is IncrementalCompiler.Result.Success) { "fixture compile failed" }

		// Still listed in allSources but gone from disk (an editor race CoGo cannot
		// prevent): the missing file must surface as an ordinary compile failure the
		// client can render, never as a daemon-killing throw.
		assertThat(widget.delete()).isTrue()
		val result = compiler.compile(sources, changedFiles = emptyList())

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Failed::class.java)
		assertThat((result as IncrementalCompiler.Result.Failed).diagnostics).isNotEmpty()
	}

	@Test
	fun `a success without timings encodes as numeric zeros the client reads back as measured`() {
		// "0 means unmeasured, never -1 and never a string" is a wire contract, so assert it on
		// the wire: the same keys DaemonService.compile writes, through the real encoder, read
		// back the way DaemonProcessClient reads them (JSON-number guard, else null).
		val success =
			IncrementalCompiler.Result.Success(
				classesDir = File("/classes"),
				warnings = emptyList(),
				changedClassFiles = emptyList(),
			)

		val encoded =
			ProtocolCodec.encode(
				DaemonResponse.ok(
					id = 7L,
					values =
						mapOf(
							"classesDir" to success.classesDir.absolutePath,
							"kotlinMillis" to success.kotlinMillis,
							"javaMillis" to success.javaMillis,
						) + success.stats.toValues(),
				),
			)

		val json = JsonParser.parseString(encoded).asJsonObject
		val readLong = { key: String -> json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong }
		assertThat(readLong("kotlinMillis")).isEqualTo(0L)
		assertThat(readLong("javaMillis")).isEqualTo(0L)
		// Present-and-zero, not absent: null here would tell the client this daemon predates
		// the stats group, and a -1 sentinel in any field would fail the equality.
		assertThat(CompileStats.fromValues(readLong)).isEqualTo(CompileStats())
	}

	@Test
	fun `the logger routes each channel to its collection with a level tag`() {
		val logger = IncrementalCompiler.CollectingLogger()

		logger.error("boom", null)
		logger.warn("careful", null)
		logger.info("fyi")
		logger.debug("details")
		logger.lifecycle("phase")

		// errors/warnings feed structured diagnostics; lines feed lastCompileLog.
		assertThat(logger.errors).containsExactly("boom")
		assertThat(logger.warnings).containsExactly("careful")
		assertThat(logger.lines)
			.containsExactly("e: boom", "w: careful", "i: fyi", "d: details", "l: phase")
			.inOrder()
		assertThat(logger.isDebugEnabled).isTrue()
	}

	@Test
	fun `removing a java source deletes its nested classes but not a sibling's outputs`() {
		val widget =
			writeJava(
				"main/java/demo/Widget.java",
				"package demo;\n\npublic class Widget {\n\tpublic class Inner {}\n}\n",
			)
		val sibling = writeJava("main/java/demo/Widget2.java", "package demo;\n\npublic class Widget2 {}\n")
		val compiler = compiler()
		val first = compiler.compile(listOf(widget, sibling), changedFiles = listOf(widget, sibling))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/Widget\$Inner.class").isFile).isTrue()
		// A non-class file sharing the nested-class prefix must survive the sweep.
		val notes = File(classesDir, "demo/Widget\$notes.txt").apply { writeText("keep") }

		assertThat(widget.delete()).isTrue()
		val result = compiler.compile(listOf(sibling), changedFiles = emptyList(), removedFiles = listOf(widget))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "demo/Widget.class").exists()).isFalse()
		assertThat(File(classesDir, "demo/Widget\$Inner.class").exists()).isFalse()
		assertThat(File(classesDir, "demo/Widget2.class").isFile).isTrue()
		assertThat(notes.isFile).isTrue()
	}

	@Test
	fun `a removed java path with no source-root marker is skipped without touching outputs`() {
		val widget = widgetJava()
		val compiler = compiler()
		val first = compiler.compile(listOf(widget), changedFiles = listOf(widget))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/Widget.class").isFile).isTrue()

		// No java/kotlin segment anywhere: the stem cannot be derived, so nothing may be
		// guessed at and deleted.
		val unrooted = File(tempDir, "flat/demo/Widget.java")
		val result = compiler.compile(listOf(widget), changedFiles = emptyList(), removedFiles = listOf(unrooted))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "demo/Widget.class").isFile).isTrue()
	}

	@Test
	fun `a removed java under a non-main java root falls back to the last root marker`() {
		val widget = widgetJava("custom/java/demo/Widget.java")
		val compiler = compiler()
		val first = compiler.compile(listOf(widget), changedFiles = listOf(widget))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/Widget.class").isFile).isTrue()

		assertThat(widget.delete()).isTrue()
		val result = compiler.compile(emptyList(), changedFiles = emptyList(), removedFiles = listOf(widget))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "demo/Widget.class").exists()).isFalse()
	}

	@Test
	fun `a removed java under a kotlin source root maps its package the same way`() {
		// Mixed layouts put .java files under src/main/kotlin too; the root marker
		// accepts either directory name.
		val widget = widgetJava("main/kotlin/demo/Widget.java")
		val compiler = compiler()
		val first = compiler.compile(listOf(widget), changedFiles = listOf(widget))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/Widget.class").isFile).isTrue()

		assertThat(widget.delete()).isTrue()
		val result = compiler.compile(emptyList(), changedFiles = emptyList(), removedFiles = listOf(widget))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "demo/Widget.class").exists()).isFalse()
	}

	@Test
	fun `a rootless relative removed path still maps its package via the leading marker`() {
		val widget = widgetJava()
		val compiler = compiler()
		val first = compiler.compile(listOf(widget), changedFiles = listOf(widget))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/Widget.class").isFile).isTrue()

		assertThat(widget.delete()).isTrue()
		val result =
			compiler.compile(
				emptyList(),
				changedFiles = emptyList(),
				removedFiles = listOf(File("java/demo/Widget.java")),
			)

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "demo/Widget.class").exists()).isFalse()
	}

	@Test
	fun `a vanished classes dir mid-session is rebuilt, not tripped over`() {
		// External cleanup (or a first-ever build) can leave the output tree absent when a
		// compile starts: the pre-snapshot and the removed-java sweep must both treat
		// "no tree" as "no outputs" and the compile must recreate it.
		val kotlin = kotlinSource()
		val compiler = compiler()
		val ghostRemoved = File(srcDir, "main/java/demo/Old.java")
		File(workDir, "classes").deleteRecursively()

		val result =
			compiler.compile(listOf(kotlin), changedFiles = listOf(kotlin), removedFiles = listOf(ghostRemoved))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val success = result as IncrementalCompiler.Result.Success
		assertThat(File(success.classesDir, "demo/Greeter.class").isFile).isTrue()
		assertThat(success.changedClassFiles).contains("demo/Greeter.class")
	}

	@Test
	fun `a removed java whose package never produced output is a no-op`() {
		val kotlin = kotlinSource()
		val compiler = compiler()
		val first = compiler.compile(listOf(kotlin), changedFiles = listOf(kotlin))
		check(first is IncrementalCompiler.Result.Success) { "fixture compile failed" }

		val ghost = File(srcDir, "main/java/ghost/Gone.java")
		val result = compiler.compile(listOf(kotlin), changedFiles = emptyList(), removedFiles = listOf(ghost))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
	}
}
