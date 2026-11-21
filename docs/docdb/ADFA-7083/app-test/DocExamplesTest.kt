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
import java.util.concurrent.atomic.AtomicBoolean
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
			withTimeout(10_000) {
				val runaway = launch { cancellable { products.spin() } }
				delay(500)
				assertTrue("spin is still running", runaway.isActive)
				runaway.cancelAndJoin()
			}

			// RunawayQuery, as a Cancel button would use it; the database works afterwards
			val runaway = RunawayQuery(CoroutineScope(Dispatchers.Default), products)
			runaway.start()
			delay(500)
			runaway.cancel()
			withTimeout(10_000) { assertEquals(4, cancellable { products.slugs() }.size) }
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
		val canceller = Thread {
			Thread.sleep(300)
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
				raw.execSQL("INSERT INTO Product (name, price) VALUES ('Extra', 1.0)")
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

	// What the page leaves open, logged for CLAIMS.md: what Room reports when SQLite has already
	// rolled the transaction back (a write whose collation is stopped ends with SQLITE_INTERRUPT),
	// and whether cancelling a coroutine without cancellable() ends a query that never returns.
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
				val canceller = Thread {
					Thread.sleep(300)
					while (!stopped.get()) {
						SqliteJs.cancelAll()
						Thread.sleep(10)
					}
				}
				canceller.start()
				var inner = "none"
				val block = {
					val raw = db.openHelper.writableDatabase
					raw.execSQL("INSERT INTO Product (name, price) VALUES ('Extra', 1.0)")
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
					canceller.join()
				}
				Log.i("DocExamples", "collation write in $how: statement threw [$inner], $how threw [$outer]")
				assertTrue("$how: the statement failed: $inner", inner != "none")
				assertTrue("$how: the block's failure left $how: $outer", outer != "none")
				assertEquals("$how: nothing of the transaction is kept", 4, db.count())
			}

			val plain = launch(Dispatchers.Default) { db.products().spin() }
			delay(500)
			plain.cancel()
			delay(1000)
			Log.i("DocExamples", "1 s after a plain cancel of spin(): job completed = ${plain.isCompleted}")
			withTimeout(10_000) {
				while (!plain.isCompleted) {
					SqliteJs.cancelAll()
					delay(10)
				}
			}
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
