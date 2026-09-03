package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.gradle.quickbuild.ClassOpener
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider

/**
 * Catches a one-sided source edit between the daemon's [FinalStripper] and the proxy build's
 * ClassOpener, which are deliberate duplicates - a shared module would drag a second jar into
 * the gradle-plugin's flatDir init-script bundle.
 *
 * It does NOT pin the bytes the two produce in production. ASM is `compileOnly` on both sides
 * and not exported, so this test runs both transforms against the daemon's ASM; in a real build
 * each side gets its own. What it does pin is that the two sources still agree when handed the
 * same ASM, which is where a one-sided edit shows up. The dex verifier depends on their outputs
 * agreeing - the gen-0 baseline carries ClassOpener's bytes and every hot recompile carries
 * FinalStripper's - and an edit to one alone otherwise surfaces on device as a verifier
 * rejection.
 */
class FinalStripperClassOpenerParityTest {
	/**
	 * Compiles one fixture per shape the transform distinguishes.
	 *
	 * @return every `.class` produced, sorted by name: three top-level classes, plus the nested
	 *   fixture's inner class, which javac emits as its own file.
	 */
	private fun compileFixtures(): List<File> {
		val dir = Files.createTempDirectory("stripper-parity").toFile()
		File(dir, "FinalFixture.java")
			.writeText("public final class FinalFixture { public final int v() { return 3; } }")
		File(dir, "OpenFixture.java").writeText("public class OpenFixture { public int f() { return 7; } }")
		File(dir, "Nested.java").writeText("public class Nested {\n\tpublic static final class Inner {}\n}\n")
		val sources = dir.listFiles { file -> file.name.endsWith(".java") }!!.map { it.absolutePath }
		val compiler = ToolProvider.getSystemJavaCompiler()
		check(compiler.run(null, null, null, "-d", dir.absolutePath, *sources.toTypedArray()) == 0) {
			"test fixtures failed to compile"
		}
		return dir.listFiles { file -> file.name.endsWith(".class") }!!.sortedBy { it.name }
	}

	@Test
	fun `both transforms produce identical bytes over the same classes`() {
		val classFiles = compileFixtures()
		// FinalFixture, OpenFixture, and the nested pair's two files (Nested, Nested$Inner).
		assertThat(classFiles).hasSize(4)
		for (classFile in classFiles) {
			val bytes = classFile.readBytes()
			assertWithMessage("transform outputs diverge for ${classFile.name}")
				.that(FinalStripper.strip(bytes))
				.isEqualTo(ClassOpener.stripFinalModifier(bytes))
		}
	}
}
