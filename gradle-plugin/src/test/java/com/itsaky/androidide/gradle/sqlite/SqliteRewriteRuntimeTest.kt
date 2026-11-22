package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.utils.FileProvider
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException

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

	/** The signal's listener, read from the signal itself: a call on FORWARDERS would expunge its stale entries. */
	private fun listenerOf(signal: Any): Any? = androidSignal.getDeclaredField("listener").apply { isAccessible = true }.get(signal)

	private fun setListener(
		signal: Any,
		listener: Any?,
	) {
		val type = loader.loadClass("android.os.CancellationSignal\$OnCancelListener")
		androidSignal.getMethod("setOnCancelListener", type).invoke(signal, listener)
	}

	/** Converts a signal that nothing else holds, in its own frame, and returns a weak reference to its forwarder. */
	private fun forwarderOfDroppedSignal(): WeakReference<Any> {
		val signal = newAndroidSignal()
		convert(signal)
		return WeakReference(listenerOf(signal)!!)
	}

	/**
	 * Requests GC until [ref] clears, at most [rounds] times. Timing-dependent only in the passing direction: HotSpot's
	 * System.gc() is a full collection and clears a weak reference in the first round, and the bound only ends a run in
	 * which it never clears. Nothing calls FORWARDERS meanwhile, as any call on it expunges the stale entries.
	 */
	private fun clearedAfterGc(
		ref: WeakReference<*>,
		rounds: Int = 50,
	): Boolean {
		repeat(rounds) {
			if (ref.get() == null) return true
			System.gc()
			Thread.sleep(20)
		}
		return ref.get() == null
	}

	private fun translate(flags: Int) = runtime.getMethod("translateOpenFlags", Int::class.javaPrimitiveType).invoke(null, flags) as Int

	@Test
	fun `deleteDatabase with a null context throws the NullPointerException, not false`() {
		val method = runtime.getMethod("deleteDatabase", loader.loadClass("android.content.Context"), String::class.java)
		val e = assertThrows(InvocationTargetException::class.java) { method.invoke(null, null, "x.db") }
		assertThat(e.cause).isInstanceOf(NullPointerException::class.java)
	}

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

	@Test
	fun `the forwarder of a signal the app dropped is not kept`() {
		val forwarder = forwarderOfDroppedSignal()

		assertWithMessage("CancelForwarder of a dropped signal, kept by the value of FORWARDERS' stale entry")
			.that(clearedAfterGc(forwarder))
			.isTrue()
	}

	@Test
	fun `a live signal keeps its forwarder through a GC and still cancels its queries`() {
		val signal = newAndroidSignal()
		val converted = convert(signal)
		val forwarder = WeakReference(listenerOf(signal))

		assertWithMessage("forwarder collected while its signal lives").that(clearedAfterGc(forwarder, rounds = 3)).isFalse()
		cancel(signal)

		assertThat(isCanceled(converted)).isTrue()
	}

	@Test
	fun `a signal whose forwarder was collected gets a new one`() {
		val signal = newAndroidSignal()
		convert(signal)
		val first = WeakReference(listenerOf(signal))
		// the caller sets its own listener, so nothing holds the first forwarder any more
		setListener(signal, null)
		assertWithMessage("first forwarder collected").that(clearedAfterGc(first)).isTrue()

		val converted = convert(signal)
		cancel(signal)

		assertWithMessage("a query converted after the forwarder was collected").that(isCanceled(converted)).isTrue()
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
