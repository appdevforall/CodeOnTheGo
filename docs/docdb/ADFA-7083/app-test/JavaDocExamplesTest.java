// Runs the Java examples of the app pages on a device. test_app_examples.py copies this file
// into its Java project, next to the examples, and runs it when DEVICE=1.
package docexamples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.requery.android.database.sqlite.SQLiteCustomExtension;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.appdevforall.sqlitejs.SqliteJs;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class JavaDocExamplesTest {

	private static Context context() {
		return InstrumentationRegistry.getInstrumentation().getTargetContext();
	}

	private static List<Product> sample() {
		return Arrays.asList(
				new Product("Red Apple", 0.5),
				new Product("Whole Wheat Bread", 3.25),
				new Product("Milk, 1 litre", 1.2),
				new Product("Coffee & Tea Sampler", 12.0));
	}

	private static String text(SQLiteDatabase db, String sql) {
		try (Cursor cursor = db.rawQuery(sql, new Object[0])) {
			return cursor.moveToFirst() ? cursor.getString(0) : null;
		}
	}

	private static String failure(SQLiteDatabase db, String sql) {
		try {
			text(db, sql);
		} catch (Exception e) {
			return String.valueOf(e.getMessage());
		}
		fail("no exception was thrown by " + sql);
		return "";
	}

	// whether a thread is in sqlite-android's native code for the DAO's spin() query, and for no
	// other statement: the stack must have Room's generated spin() too
	private static boolean spinIsRunning() {
		for (StackTraceElement[] stack : Thread.getAllStackTraces().values()) {
			boolean inSqlite = false;
			boolean inSpin = false;
			for (StackTraceElement frame : stack) {
				inSqlite |= frame.getClassName().endsWith(".SQLiteConnection") && frame.getMethodName().startsWith("nativeExecute");
				inSpin |= frame.getClassName().endsWith("ProductDao_Impl") && frame.getMethodName().contains("spin");
			}
			if (inSqlite && inSpin) {
				return true;
			}
		}
		return false;
	}

	// waits until spin() is, or is no longer, running in SQLite
	private static void awaitSpin(boolean running) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (spinIsRunning() != running) {
			assertTrue("spin() running = " + running + " within 60 s", System.nanoTime() < end);
			Thread.sleep(20);
		}
	}

	// what get() throws, on another thread so that a get() that never returns fails the test
	private static Throwable thrownByGet(CancellableQuery<?> query) throws Exception {
		ExecutorService waiter = Executors.newSingleThreadExecutor();
		try {
			Future<Throwable> thrown = waiter.submit(() -> {
				try {
					query.get();
					return null;
				} catch (Throwable t) {
					return t;
				}
			});
			return thrown.get(10, TimeUnit.SECONDS);
		} finally {
			waiter.shutdownNow();
		}
	}

	@Test
	public void roomCallsTheStoredFunctionsAndCancelStopsARunaway() throws Exception {
		context().deleteDatabase(ProductFunctions.NAME);
		ProductDatabase db = ProductDatabases.open(context());
		try {
			ProductDao products = db.products();
			products.insert(sample());
			assertEquals(
					Arrays.asList("red-apple", "whole-wheat-bread", "milk-1-litre", "coffee-tea-sampler"),
					products.slugs());
			assertEquals(2.225, products.medianPrice(), 1e-9);

			// a query that ends gives its result through get(), also after a cancel() that came too late
			CancellableQuery<List<String>> ended = new CancellableQuery<>(products::slugs);
			assertEquals(4, ended.get().size());
			ended.cancel();
			assertEquals(4, ended.get().size());

			// cancel() stops JavaScript that never ends, and get() then reports a cancel
			CancellableQuery<Integer> spin = new CancellableQuery<>(products::spin);
			awaitSpin(true);
			spin.cancel();
			spin.cancel();
			Throwable thrown = thrownByGet(spin);
			assertTrue(String.valueOf(thrown), thrown instanceof CancellationException);
			// the query's own error is its cause
			assertTrue(String.valueOf(thrown.getCause()),
					thrown.getCause() != null && String.valueOf(thrown.getCause().getMessage()).contains("interrupted"));

			// cancel() does not wait for the query: this one cannot end until the latch opens, so
			// a cancel() that waited would never return. A query that ends with a result after
			// the cancel gives that result.
			CountDownLatch latch = new CountDownLatch(1);
			CancellableQuery<Integer> waiting = new CancellableQuery<>(() -> {
				latch.await();
				return 7;
			});
			ExecutorService canceller = Executors.newSingleThreadExecutor();
			try {
				canceller.submit(waiting::cancel).get(60, TimeUnit.SECONDS);
			} finally {
				canceller.shutdownNow();
			}
			latch.countDown();
			assertEquals(Integer.valueOf(7), waiting.get());

			// a query that fails by itself is not reported as cancelled
			CancellableQuery<Integer> broken = new CancellableQuery<>(() -> {
				throw new IllegalStateException("broken");
			});
			thrown = thrownByGet(broken);
			assertTrue(String.valueOf(thrown), thrown instanceof ExecutionException);

			// RunawayQuery, as a Cancel button would use it; the database works afterwards
			RunawayQuery runaway = new RunawayQuery(products);
			runaway.cancel();
			runaway.start();
			awaitSpin(true);
			runaway.cancel();
			// its cancelAll() calls end with its query; a moment more, so that none reaches slugs()
			awaitSpin(false);
			Thread.sleep(300);
			assertEquals(4, products.slugs().size());

			// a write stopped inside runInTransaction: Room rolls the transaction back
			CancellableQuery<Object> write = new CancellableQuery<>(() -> {
				db.runInTransaction(() -> {
					db.getOpenHelper().getWritableDatabase()
							.execSQL("INSERT INTO Product (name, price) VALUES ('Extra', 1.0)");
					db.getOpenHelper().getWritableDatabase().execSQL("UPDATE Product SET price = spin()");
				});
				return null;
			});
			Thread.sleep(500);
			write.cancel();
			assertTrue(thrownByGet(write) instanceof CancellationException);
			// a moment, so that the last cancelAll() of that cancel cannot reach slugs()
			Thread.sleep(300);
			assertEquals(4, products.slugs().size());
		} finally {
			db.close();
		}

		// storing again, as on the app's next start, changes nothing and does not fail
		ProductFunctions.store(context());
		ProductDatabase again = ProductDatabases.open(context());
		try {
			assertEquals(4, again.products().slugs().size());
		} finally {
			again.close();
		}
	}

	@Test
	public void entryPointRunsNothingStoredInASharedFile() {
		File file = new File(context().getCacheDir(), "shared.db");
		file.delete();
		new File(file.getPath() + "-journal").delete();
		// another party's file: it stores a function
		SQLiteDatabaseConfiguration config = new SQLiteDatabaseConfiguration(
				file.getPath(), SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.OPEN_READWRITE);
		config.customExtensions.add(new SQLiteCustomExtension(SqliteJs.libraryPath(context()), SqliteJs.ENTRY_POINT));
		try (SQLiteDatabase db = SQLiteDatabase.openDatabase(config, null, null)) {
			text(db, "SELECT js_init_table()");
			text(db, "SELECT js_create_scalar('stored', '(function (args) { return 1; })')");
		}

		try (SQLiteDatabase db = SharedFiles.open(context(), file)) {
			assertEquals("1.4.4", text(db, "SELECT js_version()"));
			assertEquals("42", text(db, "SELECT js_eval('6 * 7')"));
			assertTrue(failure(db, "SELECT stored()").contains("no such function"));
			assertTrue(db.isReadOnly());
		}
	}
}
