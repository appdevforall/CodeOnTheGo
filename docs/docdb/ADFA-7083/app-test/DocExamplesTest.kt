// Runs the Kotlin examples of the app pages on a device. test_app_examples.py copies this file
// into the template project, next to the examples, when DEVICE=1.
package docexamples

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.appdevforall.sqlitejs.SqliteJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocExamplesTest {
	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	private val sample = listOf(
		Product(name = "Red Apple", price = 0.5),
		Product(name = "Whole Wheat Bread", price = 3.25),
		Product(name = "Milk, 1 litre", price = 1.2),
		Product(name = "Coffee & Tea Sampler", price = 12.0),
	)

	private fun SQLiteDatabase.text(sql: String): String? =
		rawQuery(sql, emptyArray<Any>()).use { if (it.moveToFirst()) it.getString(0) else null }

	private fun failure(run: () -> Unit): String {
		try {
			run()
		} catch (e: Exception) {
			return e.message ?: e.toString()
		}
		fail("no exception was thrown")
		return ""
	}

	@Test
	fun roomCallsTheStoredFunctionsAndCancelStopsARunaway() = runBlocking {
		context.deleteDatabase("products.db")
		val db = openProductDatabase(context)
		try {
			val products = db.products()
			products.insert(sample)
			assertEquals(
				listOf("red-apple", "whole-wheat-bread", "milk-1-litre", "coffee-tea-sampler"),
				products.slugs(),
			)
			assertEquals(2.225, products.medianPrice()!!, 1e-9)

			// cancellable(): cancelling the coroutine stops JavaScript that never ends
			withTimeout(120_000) {
				val runaway = launch { cancellable { products.spin() } }
				awaitSpin(true)
				assertTrue("spin is still running", runaway.isActive)
				runaway.cancelAndJoin()
			}

			// RunawayQuery, as a Cancel button would use it; the database works afterwards
			val runaway = RunawayQuery(CoroutineScope(Dispatchers.Default), products)
			runaway.start()
			awaitSpin(true)
			runaway.cancel()
			// its cancelAll() calls end with its query; a moment more, so that none reaches slugs()
			awaitSpin(false)
			delay(300)
			withTimeout(60_000) { assertEquals(4, cancellable { products.slugs() }.size) }
		} finally {
			db.close()
		}

		// storing again, as on the app's next start, changes nothing and does not fail
		storeFunctions(context)
		// a second database object sees the stored functions on its new connections
		val again = openProductDatabase(context)
		try {
			assertEquals(4, again.products().slugs().size)
		} finally {
			again.close()
		}
	}

	private fun ProductDatabase.count(): Int =
		openHelper.writableDatabase.query("SELECT count(*) FROM Product").use { it.moveToFirst(); it.getInt(0) }

	// Runs, inside a transaction that transaction() opens, an insert and then an UPDATE whose
	// JavaScript never ends, which cancelAll() stops. Returns the row count the transaction saw
	// after the UPDATE failed, the UPDATE's error, and the row count after the transaction.
	private fun cancelledWrite(db: ProductDatabase, transaction: (block: () -> Unit) -> Unit): Triple<Int, String, Int> {
		val stopped = AtomicBoolean(false)
		// cancels only once the plain INSERT has returned, so that no cancel can reach it; a cancel
		// before the UPDATE's JavaScript starts does nothing, so it is called until the UPDATE ends
		val inserted = CountDownLatch(1)
		val canceller = Thread {
			inserted.await()
			while (!stopped.get()) {
				SqliteJs.cancelAll()
				Thread.sleep(10)
			}
		}
		canceller.start()
		var inside = -1
		var message = ""
		try {
			transaction {
				val raw = db.openHelper.writableDatabase
				try {
					raw.execSQL("INSERT INTO Product (name, price) VALUES ('Extra', 1.0)")
				} finally {
					inserted.countDown()
				}
				try {
					raw.execSQL("UPDATE Product SET price = spin()")
					stopped.set(true)
					fail("the UPDATE ended without an error")
				} catch (e: Exception) {
					stopped.set(true)
					message = e.message ?: e.toString()
					inside = db.count()
					throw e
				}
			}
		} catch (e: Exception) {
			// the block's error leaves the transaction, as the page says to let it
		} finally {
			stopped.set(true)
			inserted.countDown()
			canceller.join()
		}
		return Triple(inside, message, db.count())
	}

	@Test
	fun aStoppedWriteLeavesItsTransactionOpenAndRoomRollsItBack() = runBlocking {
		context.deleteDatabase("products.db")
		val db = openProductDatabase(context)
		try {
			db.products().insert(sample)
			for (how in listOf("withTransaction", "runInTransaction")) {
				val (inside, message, after) = cancelledWrite(db) { block ->
					if (how == "withTransaction") runBlocking(Dispatchers.IO) { db.withTransaction { block() } }
					else db.runInTransaction { block() }
				}
				assertTrue("$how: $message", message.contains("interrupted"))
				assertEquals("$how: the transaction stayed open, with its earlier insert", 5, inside)
				assertEquals("$how: Room rolled the transaction back", 4, after)
			}
		} finally {
			db.close()
		}
	}

	// whether a thread is in sqlite-android's native code for the DAO's spin() query, and for no
	// other statement: the stack must have Room's generated spin() too
	private fun spinIsRunning(): Boolean =
		Thread.getAllStackTraces().values.any { stack ->
			stack.any { it.className.endsWith(".SQLiteConnection") && it.methodName.startsWith("nativeExecute") } &&
				stack.any { it.className.endsWith("ProductDao_Impl") && it.methodName.contains("spin") }
		}

	// waits until spin() is, or is no longer, running in SQLite
	private suspend fun awaitSpin(running: Boolean) {
		withTimeout(60_000) {
			while (spinIsRunning() != running) delay(20)
		}
	}

	// cancellable() drops a result that arrives after the cancel: the Java CancellableQuery keeps it
	@Test
	fun cancellableDropsAResultThatArrivesAfterTheCancel() = runBlocking {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val ended = AtomicReference<Result<Int>?>(null)
		val job = CoroutineScope(Dispatchers.Default).launch {
			try {
				ended.set(Result.success(cancellable { entered.countDown(); release.await(); 7 }))
			} catch (e: CancellationException) {
				ended.set(Result.failure(e))
				throw e
			}
		}
		try {
			// the query is running, so cancellable() is waiting for it, or about to
			val running = withContext(Dispatchers.IO) { entered.await(60, TimeUnit.SECONDS) }
			assertTrue("the query started", running)
			delay(200)
			job.cancel()
			// cancellable() goes on calling cancelAll() until the query ends: it ends when released
			delay(200)
			assertTrue("cancellable() waits for the query to end", !job.isCompleted)
		} finally {
			// whatever failed above, the query ends, and with it cancellable()'s cancelAll() calls
			release.countDown()
		}
		withTimeout(60_000) { job.join() }
		assertTrue("ended as cancelled, without the 7: ${ended.get()}", ended.get()?.exceptionOrNull() is CancellationException)
	}

	// What Room reports when SQLite has already rolled the transaction back (a write whose
	// collation is stopped ends with SQLITE_INTERRUPT), and that cancelling a coroutine without
	// cancellable() does not end a query that never returns.
	@Test
	fun whatRoomDoesWhenSqliteRolledBackAndWhenAPlainCoroutineIsCancelled() = runBlocking {
		context.deleteDatabase("products.db")
		storeFunctions(context)
		val config = SQLiteDatabaseConfiguration(context.getDatabasePath("products.db").path, SQLiteDatabase.OPEN_READWRITE)
		config.customExtensions.add(SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT))
		SQLiteDatabase.openDatabase(config, null, null).use { raw ->
			raw.text("SELECT js_create_collation('spin_collation', '(function (a, b) { for (;;) {} })')")
		}
		val db = openProductDatabase(context)
		try {
			db.products().insert(sample)
			for (how in listOf("withTransaction", "runInTransaction")) {
				val stopped = AtomicBoolean(false)
				// cancels only once the plain INSERT has returned, so that no cancel can reach it;
				// a cancel before the collation starts does nothing, so it is called until the end
				val inserted = CountDownLatch(1)
				val canceller = Thread {
					inserted.await()
					while (!stopped.get()) {
						SqliteJs.cancelAll()
						Thread.sleep(10)
					}
				}
				canceller.start()
				var inner = "none"
				val block = {
					val raw = db.openHelper.writableDatabase
					try {
						raw.execSQL("INSERT INTO Product (name, price) VALUES ('Extra', 1.0)")
					} finally {
						inserted.countDown()
					}
					try {
						raw.execSQL("INSERT INTO Product (name, price) SELECT name, price FROM Product ORDER BY name COLLATE spin_collation")
					} catch (e: Exception) {
						inner = "${e.javaClass.name}: ${e.message}"
						throw e
					} finally {
						stopped.set(true)
					}
				}
				val outer = try {
					if (how == "withTransaction") withContext(Dispatchers.IO) { db.withTransaction { block() } }
					else withContext(Dispatchers.IO) { db.runInTransaction { block() } }
					"none"
				} catch (e: Exception) {
					"${e.javaClass.name}: ${e.message}"
				} finally {
					stopped.set(true)
					inserted.countDown()
					canceller.join()
				}
				Log.i("DocExamples", "collation write in $how: statement threw [$inner], $how threw [$outer]")
				assertTrue("$how: the statement was interrupted: $inner", inner.contains("interrupted"))
				assertTrue("$how: Room's rollback failed: $outer", outer.contains("cannot rollback"))
				assertEquals("$how: nothing of the transaction is kept", 4, db.count())
			}

			// A plain cancel, without cancellable(). In a scope of its own: its failure, once
			// cancelAll() stops it, is not this test's.
			val ended = AtomicReference<Result<Int?>?>(null)
			val plain = CoroutineScope(Dispatchers.Default).launch {
				ended.set(runCatching { db.products().spin() })
			}
			try {
				// cancel only once spin() is running in SQLite: a cancel before the query started
				// would end the job at once, and prove nothing
				awaitSpin(true)
				plain.cancel()
				// spin() never returns, so no wait is too short for this check: it fails only if
				// the cancel ended the query
				delay(2000)
				Log.i("DocExamples", "2 s after a plain cancel of spin(): job completed = ${plain.isCompleted}")
				assertTrue("a plain cancel left spin() running", !plain.isCompleted && ended.get() == null)
			} finally {
				// only cancelAll() ends it; also after a failure above, or spin() would run on
				withTimeout(60_000) {
					while (!plain.isCompleted) {
						SqliteJs.cancelAll()
						delay(10)
					}
				}
			}
			// it ended as a failure, not with a value
			assertTrue("spin() ended by failing: ${ended.get()}", ended.get()?.isFailure == true)
			// spin() keeps its connection busy until cancelAll() stops it: the database answers again
			repeat(20) { SqliteJs.cancelAll(); delay(10) }
			assertEquals(4, db.count())
		} finally {
			db.close()
		}
	}

	@Test
	fun entryPointRunsNothingStoredInASharedFile() {
		val file = File(context.cacheDir, "shared.db")
		file.delete()
		File(file.path + "-journal").delete()
		// another party's file: it stores a function
		val config = SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE)
		config.customExtensions.add(SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT))
		SQLiteDatabase.openDatabase(config, null, null).use { db ->
			db.text("SELECT js_init_table()")
			db.text("SELECT js_create_scalar('stored', '(function (args) { return 1; })')")
			// rawQuery runs nothing until its cursor is read
			db.rawQuery("SELECT js_create_scalar('unread', '(function (args) { return 2; })')", emptyArray<Any>()).close()
			assertTrue(failure { db.text("SELECT unread()") }.contains("no such function"))
		}

		openSharedFile(context, file).use { db ->
			assertEquals("1.4.4", db.text("SELECT js_version()"))
			assertEquals("42", db.text("SELECT js_eval('6 * 7')"))
			assertTrue(failure { db.text("SELECT stored()") }.contains("no such function"))
			// read-only, with a js_functions table: a new function cannot be stored, but works here
			val message = failure { db.text("SELECT js_create_scalar('mine', '(function (args) { return 3; })')") }
			assertTrue(message, message.contains("could not be stored in js_functions"))
			assertEquals("3", db.text("SELECT mine()"))
		}

		// the trusted entry point loads what the file stores
		val trusted = SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.OPEN_READONLY)
		trusted.customExtensions.add(SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT_FOR_TRUSTED_DATABASE))
		SQLiteDatabase.openDatabase(trusted, null, null).use { db ->
			assertEquals("1", db.text("SELECT stored()"))
		}
	}
}
