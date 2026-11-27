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
 * with Android's CancellationSignal replaced by a copy of its listener logic (android.jar's methods only throw), and
 * Context, ApplicationInfo and Build.VERSION replaced by fakes that hold what the checks read.
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
					JavaSource("android.content.Context", FAKE_CONTEXT),
					JavaSource("android.content.pm.ApplicationInfo", FAKE_APPLICATION_INFO),
					JavaSource("android.os.Build", FAKE_BUILD),
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

	private val worldReadable = 1
	private val worldWriteable = 2
	private val enableWal = 8

	private fun fakeContext(targetSdk: Int): Any {
		val context = loader.loadClass("android.content.Context").getConstructor().newInstance()
		val info = loader.loadClass("android.content.pm.ApplicationInfo").getConstructor().newInstance()
		info.javaClass.getField("targetSdkVersion").setInt(info, targetSdk)
		context.javaClass.getField("info").set(context, info)
		context.javaClass.getField("dbPath").set(context, java.io.File(System.getProperty("java.io.tmpdir"), "rewrite-runtime-test.db"))
		return context
	}

	private fun pathCalls(context: Any) = context.javaClass.getField("pathCalls").getInt(context)

	/** Calls the helper's 4-argument or 5-argument openOrCreateDatabase on a device whose API level is [sdk]. */
	private fun openOrCreate(
		context: Any,
		mode: Int,
		sdk: Int = 34,
		withHandler: Boolean = false,
	) {
		val versionClass = loader.loadClass("android.os.Build\$VERSION")
		val sdkField = versionClass.getField("SDK_INT")
		val saved = sdkField.getInt(null)
		sdkField.setInt(null, sdk)
		try {
			val contextClass = loader.loadClass("android.content.Context")
			val factory = loader.loadClass("android.database.sqlite.SQLiteDatabase\$CursorFactory")
			if (withHandler) {
				runtime
					.getMethod(
						"openOrCreateDatabase",
						contextClass,
						String::class.java,
						Int::class.javaPrimitiveType,
						factory,
						loader.loadClass("android.database.DatabaseErrorHandler"),
					).invoke(null, context, "x.db", mode, null, null)
			} else {
				runtime
					.getMethod("openOrCreateDatabase", contextClass, String::class.java, Int::class.javaPrimitiveType, factory)
					.invoke(null, context, "x.db", mode, null)
			}
		} finally {
			sdkField.setInt(null, saved)
		}
	}

	/** The failure of the call, unwrapped. Past the checks the call reaches android.jar's SQLiteDatabase, whose stub throws "Stub!". */
	private fun failureOf(block: () -> Unit): Throwable? =
		try {
			block()
			null
		} catch (e: InvocationTargetException) {
			e.cause
		}

	@Test
	fun `a world-access mode throws SecurityException before the path is resolved when the app targets API 24 or later`() {
		for (withHandler in listOf(false, true)) {
			for ((mode, message) in listOf(
				worldReadable to "MODE_WORLD_READABLE no longer supported",
				worldWriteable to "MODE_WORLD_WRITEABLE no longer supported",
				(worldReadable or worldWriteable) to "MODE_WORLD_READABLE no longer supported",
				(worldWriteable or enableWal) to "MODE_WORLD_WRITEABLE no longer supported",
			)) {
				val context = fakeContext(targetSdk = 24)
				val failure = failureOf { openOrCreate(context, mode, withHandler = withHandler) }
				assertWithMessage("mode $mode, handler $withHandler").that(failure).isInstanceOf(SecurityException::class.java)
				assertThat(failure).hasMessageThat().isEqualTo(message)
				assertWithMessage("getDatabasePath calls").that(pathCalls(context)).isEqualTo(0)
			}
		}
	}

	@Test
	fun `MODE_PRIVATE and the WAL flag still open, and an app that targets an older API or a device before API 24 gets no exception`() {
		val cases =
			listOf(
				Triple(0, 34, 30),
				Triple(enableWal, 34, 30),
				Triple(worldReadable or worldWriteable, 34, 23),
				Triple(worldReadable, 23, 30),
				Triple(worldWriteable, 23, 30),
			)
		for ((mode, sdk, target) in cases) {
			val context = fakeContext(targetSdk = target)
			val failure = failureOf { openOrCreate(context, mode, sdk = sdk) }
			// past the checks the call reaches android.jar's stub
			assertWithMessage("mode $mode, device API $sdk, target $target").that(failure).isInstanceOf(RuntimeException::class.java)
			assertWithMessage("mode $mode, device API $sdk, target $target").that(failure).isNotInstanceOf(SecurityException::class.java)
			assertThat(failure).hasMessageThat().isEqualTo("Stub!")
			assertWithMessage("getDatabasePath calls").that(pathCalls(context)).isEqualTo(1)
		}
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

		val FAKE_CONTEXT =
			"""
			package android.content;
			public class Context {
				public android.content.pm.ApplicationInfo info;
				public java.io.File dbPath;
				public int pathCalls;
				public android.content.pm.ApplicationInfo getApplicationInfo() { return info; }
				public java.io.File getDatabasePath(String name) { pathCalls++; return dbPath; }
			}
			""".trimIndent()

		val FAKE_APPLICATION_INFO =
			"""
			package android.content.pm;
			public class ApplicationInfo { public int targetSdkVersion; }
			""".trimIndent()

		val FAKE_BUILD =
			"""
			package android.os;
			public class Build { public static class VERSION { public static int SDK_INT = 34; } }
			""".trimIndent()
	}
}
