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
}
