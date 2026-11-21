// Runs the Kotlin examples of the app pages on a device. test_app_examples.py copies this file
// into the template project, next to the examples, when DEVICE=1.
package docexamples

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
