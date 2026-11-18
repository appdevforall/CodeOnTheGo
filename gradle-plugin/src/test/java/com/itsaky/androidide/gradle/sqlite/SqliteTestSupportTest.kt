package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
}
