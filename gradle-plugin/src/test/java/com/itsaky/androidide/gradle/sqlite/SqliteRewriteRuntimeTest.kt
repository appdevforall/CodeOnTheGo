package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.FileProvider
import org.junit.jupiter.api.Test

/** The pure functions of the runtime helper, run on the JVM from its bytecode compiled against android.jar. */
class SqliteRewriteRuntimeTest {
	private val runtime: Class<*> by lazy {
		val source =
			FileProvider
				.projectRoot()
				.resolve("subprojects/sqlite-rewrite-runtime/src/main/java/org/appdevforall/sqliterewrite/SqliteRewriteRuntime.java")
				.toFile()
				.readText()
		val classes =
			Compiler.compile(
				listOf(JavaSource("org.appdevforall.sqliterewrite.SqliteRewriteRuntime", source)),
				listOf(TestJars.androidJar, TestJars.androidxCoreJar),
			)
		BytecodeVerifier.loader(classes).loadClass("org.appdevforall.sqliterewrite.SqliteRewriteRuntime")
	}

	private fun translate(flags: Int) = runtime.getMethod("translateOpenFlags", Int::class.javaPrimitiveType).invoke(null, flags) as Int

	private val readOnly = 1
	private val createIfNecessary = 0x10000000
	private val wal = 0x20000000

	@Test
	fun `read-write and create keep requery's values`() {
		assertThat(translate(0)).isEqualTo(2)
		assertThat(translate(createIfNecessary)).isEqualTo(6)
		assertThat(translate(createIfNecessary or wal)).isEqualTo(6 or wal)
	}

	@Test
	fun `read-only never gets the create bit, which SQLite rejects`() {
		assertThat(translate(readOnly)).isEqualTo(1)
		assertThat(translate(readOnly or createIfNecessary)).isEqualTo(1)
	}

	@Test
	fun `other Android bits are dropped`() {
		assertThat(translate(16)).isEqualTo(2)
		assertThat(translate(8 or createIfNecessary)).isEqualTo(6)
	}
}
