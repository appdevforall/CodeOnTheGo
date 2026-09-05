package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.protocol.ProtocolCodec
import org.appdevforall.cotg.quickbuild.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
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

	private fun kotlinSource(greeting: String = "hi"): File =
		File(srcDir, "Greeter.kt").apply {
			writeText("package demo\n\nclass Greeter { fun hi() = \"$greeting\" }\n")
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
	fun `a construction that fails mid-snapshot does not commit the new classpath fingerprint`() {
		// Seed a session so the fingerprint on disk describes snapshots that really exist.
		compiler()
		val fingerprintFile = File(workDir, "classpath-fingerprint.txt")
		val seeded = fingerprintFile.readText()

		// Change the classpath (a copy at a new path fingerprints differently) and block the
		// snapshot dir with a plain file, so the per-jar snapshot seed throws mid-construction.
		val movedStdlib = TestSdk.kotlinStdlib().copyTo(File(tempDir, "moved-stdlib.jar"))
		val snapshotDir = File(workDir, "cp-snap")
		snapshotDir.deleteRecursively()
		snapshotDir.writeText("in the way")

		val thrown = runCatching { IncrementalCompiler(listOf(movedStdlib), workDir.toPath()) }.exceptionOrNull()
		assertThat(thrown).isNotNull()

		// The fingerprint must still describe the LAST session whose snapshots were built:
		// committed early, it would match the retry's classpath and let
		// assureNoClasspathSnapshotsChanges(true) trust snapshots that were never built.
		assertThat(fingerprintFile.readText()).isEqualTo(seeded)
	}

	@Test
	fun `a changed compiler plugin set wipes the IC caches like a changed classpath does`() {
		// Seed a session and plant a marker in its IC cache dir: surviving the next construction
		// means the caches were kept.
		compiler()
		val icMarker = File(workDir, "ic/marker").apply { writeText("seeded") }
		val fingerprintFile = File(workDir, "classpath-fingerprint.txt")
		val withoutPlugin = fingerprintFile.readText()
		// Any jar serves as a plugin here: nothing loads it until a compile runs.
		val plugin = TestSdk.kotlinStdlib().copyTo(File(tempDir, "some-compiler-plugin.jar"))

		IncrementalCompiler(listOf(TestSdk.kotlinStdlib()), workDir.toPath(), compilerPluginJars = listOf(plugin))

		// A plugin changes the bytecode kotlinc emits for sources the engine sees as unchanged,
		// so caches seeded without it are as stale as under a rewritten jar.
		assertThat(fingerprintFile.readText()).isNotEqualTo(withoutPlugin)
		assertThat(icMarker.exists()).isFalse()

		// And the same plugin set again keeps the warm caches, as an unchanged classpath does.
		val kept = File(workDir, "ic/marker").apply { writeText("kept") }
		IncrementalCompiler(listOf(TestSdk.kotlinStdlib()), workDir.toPath(), compilerPluginJars = listOf(plugin))
		assertThat(kept.exists()).isTrue()
	}

	@Test
	fun `a javac error flood is capped with a marker, like kotlinc's`() {
		// 60 unresolved types: under javac's own -Xmaxerrs (100), so every one reaches the
		// daemon, and past the module's MAX_DIAGNOSTICS (50), so the cap has to act.
		val fields = (1..60).joinToString("\n") { "\tMissing$it m$it;" }
		val flood = writeJava("main/java/demo/Flood.java", "package demo;\n\npublic class Flood {\n$fields\n}\n")

		val result = compiler().compile(listOf(flood), changedFiles = listOf(flood))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Failed::class.java)
		val diagnostics = (result as IncrementalCompiler.Result.Failed).diagnostics
		assertThat(diagnostics).hasSize(IncrementalCompiler.MAX_DIAGNOSTICS + 1)
		assertThat(diagnostics.last().message).isEqualTo("+10 more javac diagnostics elided")
	}

	@Test
	fun `a javac warning flood on a successful compile is capped with a WARNING marker`() {
		// 60 uses of a constructor deprecated for removal: javac's [removal] lint is on by
		// default, so each use is one warning under --release 17, and the compile still
		// succeeds. The marker rides Result.Success.warnings into the app's "reloaded" panel,
		// where an ERROR marker printed "error:" under a landed build and broke the core's
		// promise that a Success carries no ERROR.
		val uses = (1..60).joinToString("\n") { "\tInteger i$it = new Integer($it);" }
		val noisy = writeJava("main/java/demo/Noisy.java", "package demo;\n\npublic class Noisy {\n$uses\n}\n")

		val result = compiler().compile(listOf(noisy), changedFiles = listOf(noisy))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val warnings = (result as IncrementalCompiler.Result.Success).warnings
		assertThat(warnings).hasSize(IncrementalCompiler.MAX_DIAGNOSTICS + 1)
		assertThat(warnings.last().message).isEqualTo("+10 more javac diagnostics elided")
		assertThat(warnings.map { it.severity }.toSet()).containsExactly(Diagnostic.Severity.WARNING)
	}

	@Test
	fun `a javac error behind a warning flood survives the cap`() {
		// 60 [removal] warnings come first in source order, then one unresolved local type:
		// the error is javac's 61st diagnostic, past MAX_DIAGNOSTICS. A cap that trims by
		// position drops it and the user sees "failed" without the reason.
		val uses = (1..60).joinToString("\n") { "\t\tInteger i$it = new Integer($it);" }
		val source =
			writeJava(
				"main/java/demo/Buried.java",
				"package demo;\n\npublic class Buried {\n\tvoid m() {\n$uses\n\t\tMissingType last = null;\n\t}\n}\n",
			)

		val result = compiler().compile(listOf(source), changedFiles = listOf(source))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Failed::class.java)
		val diagnostics = (result as IncrementalCompiler.Result.Failed).diagnostics
		assertThat(diagnostics).hasSize(IncrementalCompiler.MAX_DIAGNOSTICS + 1)
		// The error leads, the warnings that were ahead of it in source order follow.
		assertThat(diagnostics.first().severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostics.first().message).contains("MissingType")
		assertThat(
			diagnostics
				.drop(1)
				.dropLast(1)
				.map { it.severity }
				.toSet(),
		).containsExactly(Diagnostic.Severity.WARNING)
		// Warnings keep their own order under the reordering.
		assertThat(diagnostics[1].message).contains("Integer(int)")
		// 61 reported, 50 kept: the 11 trimmed are all warnings.
		assertThat(diagnostics.last().message).isEqualTo("+11 more javac diagnostics elided")
		assertThat(diagnostics.last().severity).isEqualTo(Diagnostic.Severity.ERROR)
	}

	/**
	 * Compiles a one-method class into its own classes directory, so a test can use REAL class
	 * bytes as a directory classpath entry - the Kotlin snapshotter reads every class it finds
	 * with ASM, so a hand-written stub file is not a usable fixture.
	 */
	private fun compiledClasses(
		name: String,
		returnValue: Int,
	): File {
		val src = File(tempDir, "$name-src").apply { mkdirs() }
		val java =
			File(src, "demo/Lib.java").apply {
				parentFile!!.mkdirs()
				writeText("package demo;\n\npublic class Lib { public int v() { return $returnValue; } }")
			}
		val work = File(tempDir, "$name-work").apply { mkdirs() }
		val result =
			IncrementalCompiler(listOf(TestSdk.kotlinStdlib()), work.toPath())
				.compile(listOf(java), changedFiles = listOf(java))
		check(result is IncrementalCompiler.Result.Success) { "fixture compile failed" }
		return result.classesDir
	}

	@Test
	fun `a class rewritten inside a directory classpath entry changes the fingerprint`() {
		// A Kotlin module's own build/tmp/kotlin-classes/<variant> reaches the daemon as a
		// DIRECTORY, and javac needs it. Fingerprinting such an entry by its own length is
		// useless - length() on a directory is a filesystem constant - so an in-place class
		// rewrite would leave the staleness guard silent and ship stale dependents.
		val classesDir = File(tempDir, "library-classes").apply { mkdirs() }
		val target = File(classesDir, "demo/Lib.class").apply { parentFile!!.mkdirs() }
		File(compiledClasses("one", 1), "demo/Lib.class").copyTo(target, overwrite = true)
		val fingerprintFile = File(workDir, "classpath-fingerprint.txt")

		IncrementalCompiler(listOf(TestSdk.kotlinStdlib(), classesDir), workDir.toPath())
		val before = fingerprintFile.readText()

		// Same path, same class, new bytes: the in-place rewrite AGP does to a sibling library
		// module between builds.
		File(compiledClasses("two", 2), "demo/Lib.class").copyTo(target, overwrite = true)
		IncrementalCompiler(listOf(TestSdk.kotlinStdlib(), classesDir), workDir.toPath())

		assertThat(fingerprintFile.readText()).isNotEqualTo(before)
	}

	@Test
	fun `a directory classpath entry fingerprints the same when nothing inside it moved`() {
		// The other half: a re-configure over an unchanged classpath must keep the warm caches,
		// so the walk has to be deterministic rather than merely sensitive.
		val classesDir = compiledClasses("stable", 7)
		val fingerprintFile = File(workDir, "classpath-fingerprint.txt")

		IncrementalCompiler(listOf(TestSdk.kotlinStdlib(), classesDir), workDir.toPath())
		val before = fingerprintFile.readText()

		IncrementalCompiler(listOf(TestSdk.kotlinStdlib(), classesDir), workDir.toPath())

		assertThat(fingerprintFile.readText()).isEqualTo(before)
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
		val emitted = mutableListOf<String>()
		val logger = IncrementalCompiler.CollectingLogger(emitted::add)

		logger.error("boom", null)
		logger.warn("careful", null)
		logger.info("fyi")
		logger.debug("details")
		logger.lifecycle("phase")

		// errors/warnings feed structured diagnostics; every line is forwarded to the sink
		// and nothing else is retained.
		assertThat(logger.errors).containsExactly("boom")
		assertThat(logger.warnings).containsExactly("careful")
		assertThat(emitted)
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
	fun `a nested class edited out of a surviving java source leaves no stale output`() {
		// javac deletes nothing for a source it recompiles, so a declaration edited away leaves
		// its output behind - untouched, therefore invisible to the output diff, and re-dexed into
		// every later payload. Dead classes then accumulate against the single-dex ceiling, which
		// is a hard failure, and the removed class still resolves by name through the payload
		// loader.
		val widget =
			writeJava(
				"main/java/demo/Widget.java",
				"package demo;\n\npublic class Widget {\n" +
					"\tpublic class Helper {}\n" +
					"\tpublic Runnable r = new Runnable() { public void run() {} };\n" +
					"}\n",
			)
		val sibling = writeJava("main/java/demo/Widget2.java", "package demo;\n\npublic class Widget2 {}\n")
		val compiler = compiler()
		val first = compiler.compile(listOf(widget, sibling), changedFiles = listOf(widget, sibling))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		assertThat(File(classesDir, "demo/Widget\$Helper.class").isFile).isTrue()
		assertThat(File(classesDir, "demo/Widget\$1.class").isFile).isTrue()

		widget.writeText("package demo;\n\npublic class Widget {}\n")
		val result = compiler.compile(listOf(widget, sibling), changedFiles = listOf(widget))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "demo/Widget\$Helper.class").exists()).isFalse()
		assertThat(File(classesDir, "demo/Widget\$1.class").exists()).isFalse()
		// The primary class is swept too, but javac regenerates it in the same build.
		assertThat(File(classesDir, "demo/Widget.class").isFile).isTrue()
		// An untouched sibling must not be swept.
		assertThat(File(classesDir, "demo/Widget2.class").isFile).isTrue()
		// The deploy policy has to SEE the removals, or it cannot know a component's nested class
		// went away - which is why the sweep runs after the pre-snapshot.
		val changed = (result as IncrementalCompiler.Result.Success).changedClassFiles
		assertThat(changed).contains("demo/Widget\$Helper.class")
		assertThat(changed).contains("demo/Widget\$1.class")
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
	fun `output a failed compile left behind is still reported by the next successful one`() {
		// Save 0: both sides good. This is the state the caller actually deployed.
		val widget = widgetJava()
		val greeter = kotlinSource()
		val compiler = compiler()
		val sources = listOf(greeter, widget)
		check(compiler.compile(sources, changedFiles = sources) is IncrementalCompiler.Result.Success)
		val greeterClass = File(File(workDir, "classes"), "demo/Greeter.class")
		val deployedLength = greeterClass.length()

		// Save A edits both sides. Kotlin succeeds and rewrites Greeter.class; the Java edit is a
		// body-only error, so javac fails and NOTHING from this compile is deployed.
		kotlinSource("a considerably longer greeting")
		writeJava(
			"main/java/demo/Widget.java",
			"package demo;\n\npublic class Widget { public int v() { return \"nope\"; } }",
		)
		assertThat(compiler.compile(sources, changedFiles = sources))
			.isInstanceOf(IncrementalCompiler.Result.Failed::class.java)
		// The premise of the whole sequence: the failed compile left new bytecode on disk.
		assertThat(greeterClass.length()).isNotEqualTo(deployedLength)

		// Save B fixes only the Java body, leaving the Java ABI equal to the last SUCCESSFUL
		// compile's - so no Kotlin recompiles and Greeter.class is not touched again.
		widgetJava()
		val result = compiler.compile(sources, changedFiles = listOf(widget))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		val success = result as IncrementalCompiler.Result.Success
		assertThat(success.stats.kotlinToCompile).isEqualTo(0)
		// This is the first compile whose output the caller can deploy, so it owns save A's
		// class too. Re-snapshotting the tree at the top of every compile adopts those
		// undeployed classes as already-live and drops them here, and the deploy policy then
		// answers recreate where a changed component needs a restart.
		assertThat(success.changedClassFiles).contains("demo/Greeter.class")
	}

	@Test
	fun `a deleted class output is reported as changed, not silently dropped`() {
		val widget = widgetJava()
		val greeter = kotlinSource()
		val compiler = compiler()
		val first = compiler.compile(listOf(greeter, widget), changedFiles = listOf(greeter, widget))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		check(File(classesDir, "demo/Widget.class").isFile) { "fixture compile produced no Widget.class" }

		assertThat(widget.delete()).isTrue()
		val result = compiler.compile(listOf(greeter), changedFiles = emptyList(), removedFiles = listOf(widget))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "demo/Widget.class").exists()).isFalse()
		// A deletion exists only in the before-snapshot, so filtering the post-snapshot alone can
		// never surface it - and dropping a restart-sensitive component's nested class is exactly
		// the change the deploy policy has to see.
		assertThat((result as IncrementalCompiler.Result.Success).changedClassFiles)
			.contains("demo/Widget.class")
	}

	@Test
	fun `a removed path that climbs out of the output tree deletes nothing`() {
		val widget = widgetJava()
		val compiler = compiler()
		check(compiler.compile(listOf(widget), changedFiles = listOf(widget)) is IncrementalCompiler.Result.Success)
		// The output tree is <workDir>/classes, so two levels up from it is tempDir.
		val victim = File(tempDir, "outside/Bar.class").apply { parentFile!!.mkdirs() }
		victim.writeText("keep")
		val escaping = File(srcDir, "main/java/../../outside/Bar.java")

		val result = compiler.compile(listOf(widget), changedFiles = emptyList(), removedFiles = listOf(escaping))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		// The stem is a raw join of the segments after the source root, so without a containment
		// check this sweep lists and deletes outside the output tree it owns.
		assertThat(victim.isFile).isTrue()
		assertThat(File(File(workDir, "classes"), "demo/Widget.class").isFile).isTrue()
	}

	@Test
	fun `a removed java under a package named java maps against the main source root`() {
		// `main/java` wins over the deeper `java` package segment; resolving to the last marker
		// instead would map this to a bare `Bar` at the output root and leave the real output
		// behind as stale bytecode.
		val bar = writeJava("main/java/com/foo/java/Bar.java", "package com.foo.java;\n\npublic class Bar {}\n")
		val compiler = compiler()
		val first = compiler.compile(listOf(bar), changedFiles = listOf(bar))
		val classesDir = (first as IncrementalCompiler.Result.Success).classesDir
		check(File(classesDir, "com/foo/java/Bar.class").isFile) { "fixture compile produced no Bar.class" }

		assertThat(bar.delete()).isTrue()
		val result = compiler.compile(emptyList(), changedFiles = emptyList(), removedFiles = listOf(bar))

		assertThat(result).isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		assertThat(File(classesDir, "com/foo/java/Bar.class").exists()).isFalse()
	}

	@Test
	fun `two classpath jars with the same basename get a snapshot each`() {
		// Every AAR-derived classpath entry is literally `classes.jar`. Named after the basename,
		// each snapshot overwrote the last, so the list handed to the IC engine held one path N
		// times and described only the final jar.
		val stdlib = TestSdk.kotlinStdlib()
		val fromFirstAar = File(tempDir, "aar-a/classes.jar").apply { parentFile!!.mkdirs() }
		val fromSecondAar = File(tempDir, "aar-b/classes.jar").apply { parentFile!!.mkdirs() }
		stdlib.copyTo(fromFirstAar, overwrite = true)
		stdlib.copyTo(fromSecondAar, overwrite = true)

		IncrementalCompiler(listOf(fromFirstAar, fromSecondAar), workDir.toPath()).use { compiler ->
			assertThat(File(workDir, "cp-snap").listFiles()!!.map { it.name }.toSet()).hasSize(2)

			val kotlin = kotlinSource()
			assertThat(compiler.compile(listOf(kotlin), changedFiles = listOf(kotlin)))
				.isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		}
	}

	@Test
	fun `closing hands the compilation service's project state back`() {
		// The BTA contract wants a project finished once it is done with, and on the in-process
		// strategy the retained state otherwise lives for the JVM's lifetime - one project's
		// worth per re-configure, on a 2-4 GB phone. There is nothing observable left behind to
		// assert on; what this pins is that close() exists, is reached through AutoCloseable, and
		// carries a projectId the service accepts.
		val kotlin = kotlinSource()

		IncrementalCompiler(listOf(TestSdk.kotlinStdlib()), workDir.toPath()).use { compiler ->
			assertThat(compiler.compile(listOf(kotlin), changedFiles = listOf(kotlin)))
				.isInstanceOf(IncrementalCompiler.Result.Success::class.java)
		}
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

	@Test
	fun `a failed kotlin compile caps its diagnostics instead of one per use site`() {
		// Deleting a dependency makes kotlinc emit one unresolved-reference error per use site -
		// hundreds to thousands in a real app - and the whole list rides a protocol line into a
		// phone-screen panel. Bounded here the way the aapt2 and d8 paths already are.
		val useSites = IncrementalCompiler.MAX_DIAGNOSTICS * 3
		val broken =
			File(srcDir, "Broken.kt").apply {
				writeText(
					buildString {
						appendLine("package demo")
						appendLine()
						(1..useSites).forEach { appendLine("val v$it = absentSymbol$it()") }
					},
				)
			}

		val result = compiler().compile(listOf(broken), changedFiles = listOf(broken))

		val failed = result as IncrementalCompiler.Result.Failed
		assertThat(failed.diagnostics).hasSize(IncrementalCompiler.MAX_DIAGNOSTICS + 1)
		assertThat(failed.diagnostics.last().message).contains("more Kotlin diagnostics elided")
	}

	@Test
	fun `a source set with no kotlin clears the previous compile's java abi change`() {
		// DaemonService.compile reads lastJavaAbiChange to build the ok line's javaAbiChange tail,
		// so a stale set makes this compile report the previous one's Java types as its own.
		val kotlin = kotlinSource()
		val widget = widgetJava()
		val compiler = compiler()
		val sources = listOf(kotlin, widget)
		check(compiler.compile(sources, changedFiles = sources) is IncrementalCompiler.Result.Success)

		widget.writeText("package demo;\n\npublic class Widget { public int v() { return 1; } public int w() { return 2; } }")
		check(compiler.compile(sources, changedFiles = listOf(widget)) is IncrementalCompiler.Result.Success)
		assertThat(compiler.lastJavaAbiChange).contains("Widget")

		check(compiler.compile(listOf(widget), changedFiles = listOf(widget)) is IncrementalCompiler.Result.Success)

		assertThat(compiler.lastJavaAbiChange).isEmpty()
	}

	@Test
	fun `a java source outside a source root is named on the warn channel`() {
		// The stale-output sweep cannot derive a class stem for it, so its outputs go unswept -
		// the silent stale-class bug the sweep exists to prevent. Routing alone is not enough:
		// nothing pinned that the message is emitted at all, which is how an earlier version of
		// this fix reached head with the warning going to a no-op log.
		val warnings = mutableListOf<String>()
		val rootless = writeJava("Loose.java", "package demo;\n\npublic class Loose { public int v() { return 1; } }")

		IncrementalCompiler(
			listOf(TestSdk.kotlinStdlib()),
			workDir.toPath(),
			warn = { warnings += it },
		).use { compiler ->
			check(compiler.compile(listOf(rootless), changedFiles = listOf(rootless)) is IncrementalCompiler.Result.Success)
		}

		assertThat(warnings.any { it.contains("cannot derive a class output stem") && it.contains(rootless.path) }).isTrue()
	}
}
