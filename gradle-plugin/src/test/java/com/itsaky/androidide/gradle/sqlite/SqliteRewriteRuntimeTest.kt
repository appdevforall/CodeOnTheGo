package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.utils.FileProvider
import org.junit.jupiter.api.Test

/**
 * The runtime helper's functions that need no device, run on the JVM from its bytecode compiled against android.jar,
 * with Android's CancellationSignal replaced by a copy of its listener logic (android.jar's methods only throw).
 */
class SqliteRewriteRuntimeTest {
	private val loader: ClassLoader by lazy {
		val source =
			FileProvider
				.projectRoot()
				.resolve("subprojects/sqlite-rewrite-runtime/src/main/java/org/appdevforall/sqliterewrite/SqliteRewriteRuntime.java")
				.toFile()
				.readText()
		val classes =
			Compiler.compile(
				listOf(
					JavaSource("org.appdevforall.sqliterewrite.SqliteRewriteRuntime", source),
					JavaSource("android.os.CancellationSignal", ANDROID_SIGNAL),
				),
				listOf(TestJars.androidJar, TestJars.androidxCoreJar),
			)
		BytecodeVerifier.loader(classes)
	}

	private val runtime: Class<*> by lazy { loader.loadClass("org.appdevforall.sqliterewrite.SqliteRewriteRuntime") }

	private val androidSignal: Class<*> by lazy { loader.loadClass("android.os.CancellationSignal") }

	private fun newAndroidSignal(): Any = androidSignal.getConstructor().newInstance()

	private fun convert(signal: Any): Any = runtime.getMethod("toCancellationSignal", androidSignal).invoke(null, signal)

	private fun cancel(signal: Any) {
		signal.javaClass.getMethod("cancel").invoke(signal)
	}

	private fun isCanceled(signal: Any) = signal.javaClass.getMethod("isCanceled").invoke(signal) as Boolean

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

	@Test
	fun `two queries that share an Android signal are both cancelled with it`() {
		val signal = newAndroidSignal()
		// requery keeps each query's signal for its cursor's later reads
		val first = convert(signal)
		val second = convert(signal)

		cancel(signal)

		assertWithMessage("the first query's signal").that(isCanceled(first)).isTrue()
		assertWithMessage("the second query's signal").that(isCanceled(second)).isTrue()
	}

	@Test
	fun `a query given a signal that is already cancelled gets a cancelled one`() {
		val signal = newAndroidSignal()
		convert(signal)
		cancel(signal)

		assertThat(isCanceled(convert(signal))).isTrue()
	}

	private companion object {
		/** Android's listener rules: one listener, a new one runs at once on a cancelled signal, the same one does not. */
		val ANDROID_SIGNAL =
			"""
			package android.os;
			public final class CancellationSignal {
				private boolean canceled;
				private OnCancelListener listener;
				public boolean isCanceled() { synchronized (this) { return canceled; } }
				public void cancel() {
					OnCancelListener l;
					synchronized (this) {
						if (canceled) return;
						canceled = true;
						l = listener;
					}
					if (l != null) l.onCancel();
				}
				public void setOnCancelListener(OnCancelListener l) {
					synchronized (this) {
						if (listener == l) return;
						listener = l;
						if (!canceled || l == null) return;
					}
					l.onCancel();
				}
				public interface OnCancelListener { void onCancel(); }
			}
			""".trimIndent()
	}
}
