package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.gradle.quickbuild.ClassOpener
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider

/**
 * Pins the daemon's [FinalStripper] to the proxy build's ClassOpener byte for byte.
 *
 * The two are deliberate duplicates - a shared module would drag a second jar into the
 * gradle-plugin's flatDir init-script bundle - and the dex verifier depends on their outputs
 * agreeing: the gen-0 baseline carries ClassOpener's bytes and every hot recompile carries
 * FinalStripper's. A one-sided edit otherwise surfaces on device as a verifier rejection;
 * this test makes it fail at build time instead.
 */
class FinalStripperClassOpenerParityTest {
	/** Compiles one fixture per shape the transform distinguishes; returns all four .class files. */
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
		// Final, open, final-with-final-method, and the nested pair's two files.
		assertThat(classFiles).hasSize(4)
		for (classFile in classFiles) {
			val bytes = classFile.readBytes()
			assertWithMessage("transform outputs diverge for ${classFile.name}")
				.that(FinalStripper.strip(bytes))
				.isEqualTo(ClassOpener.stripFinalModifier(bytes))
		}
	}
}
