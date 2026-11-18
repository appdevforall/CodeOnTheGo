package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * The test support's own logic, which the rewrite tests rely on to find their jars and to tell a linking reference
 * from a broken one.
 */
class SqliteTestSupportTest {
	@Test
	fun `the jars are looked up in the Gradle user home of the build that runs the tests`(
		@TempDir dir: File,
	) {
		val home = File(dir, "home")
		val active = File(dir, "active-gradle-home")
		val env = File(dir, "env-gradle-home")

		assertThat(gradleUserHome(active.path, env.path, home)).isEqualTo(active)
		// run from an IDE, without the property the test tasks pass
		assertThat(gradleUserHome(null, env.path, home)).isEqualTo(env)
		assertThat(gradleUserHome(null, null, home)).isEqualTo(File(home, ".gradle"))
	}

	@Test
	fun `a constructor resolves in the named class only, while methods are still inherited`() {
		val classes =
			Compiler.compile(
				listOf(
					JavaSource("p.Parent", "package p; public class Parent { public Parent() {} public void m() {} }"),
					JavaSource("p.Child", "package p; public class Child extends Parent { public Child(int i) {} }"),
				),
				classpath = emptyList(),
			)
		val index = ClassIndex(emptyList())
		classes.values.forEach(index::addClass)

		assertThat(index.resolve("p/Child", "<init>", "(I)V", field = false)).isEqualTo("p/Child")
		// the JVM throws NoSuchMethodError for `new Child()`: Parent's constructor is not Child's
		assertThat(index.resolve("p/Child", "<init>", "()V", field = false)).isNull()
		assertThat(index.resolve("p/Child", "m", "()V", field = false)).isEqualTo("p/Parent")
	}

	@Test
	fun `a process that keeps its output open fails at its time limit, with what it printed`() {
		val started = System.nanoTime()

		val e = assertThrows(AssertionError::class.java) { runBounded(listOf("bash", "-c", "echo started; sleep 20"), timeoutSeconds = 1) }

		assertThat(e.message).contains("did not finish in 1 s")
		assertThat(e.message).contains("started")
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15))
	}

	@Test
	fun `a process that finishes gives its exit code and output`() {
		val result = runBounded(listOf("bash", "-c", "echo out; echo err >&2; exit 3"), timeoutSeconds = 30)

		assertThat(result.exitCode).isEqualTo(3)
		assertThat(result.output.lines()).containsAtLeast("out", "err")
	}
}
