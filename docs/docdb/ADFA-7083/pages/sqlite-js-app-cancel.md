---
file: sqlite-js-app-cancel.html
title: Stop JavaScript that never ends
description: How SqliteJs.cancelAll() stops a runaway JavaScript function in an app, and what else it interrupts.
parent: sqlite-js-app-top.html
prev: sqlite-js-app-database.html
---

`SqliteJs.cancelAll()` stops a JavaScript function that never returns. Neither Room nor Android's own way to cancel a query can stop one. Until the function stops, its query keeps one database connection busy.

Call `cancelAll()` only when you know that a runaway function is running, for example from a **Cancel** button. It interrupts more than one query, as this page explains.

## What cancelAll() stops

`cancelAll()` stops the JavaScript that is running at that moment, in every query, on every connection of your app.

- Each call into JavaScript that it stops fails with the error `interrupted`. JavaScript cannot catch this error.
- The connection of each such call is interrupted too. Any other statement in progress on that connection also stops.

So a query that you did not mean to stop can fail too, if it was running JavaScript at that moment. Run it again.

## A stopped statement inside a transaction

When `cancelAll()` stops a function that an `INSERT`, `UPDATE` or `DELETE` called, that statement fails, and SQLite undoes the changes of that statement only. The transaction stays open, with the changes made before the statement.

So roll the transaction back, and run all of it again:

- Room's `withTransaction` and `runInTransaction` roll back when their block throws. Let the error leave the block.
- If your code began the transaction itself with `BEGIN`, run `ROLLBACK`.

In one case SQLite rolls the whole transaction back by itself: when the interrupt of the connection, not the function's error, ends the statement. That happens to a statement that sorts with a JavaScript collation. The transaction is then already gone:

- `ROLLBACK` fails with `cannot rollback - no transaction is active`.
- Room's own rollback may fail too, and its error may replace the `interrupted` error.

Treat either error the same way: the transaction is gone, so run all of it again.

## What cancelAll() does not stop

- **JavaScript that has not started.** A query that has not reached its JavaScript yet runs it normally afterward. So call `cancelAll()` again until the query you want to stop has ended.
- **The JavaScript that sqlite-js runs while it sets up a connection**: while it loads, and while it creates each stored function.
- **SQL that JavaScript is running through `db.exec`.** A long `next()` or `toArray()` finishes first. The cancel takes effect when JavaScript runs again.

You can call `cancelAll()` from any thread.

## Cancel in Kotlin, with a coroutine

The template's `cancellable()` runs a query so that cancelling the coroutine also stops the query's JavaScript. It calls `cancelAll()` again and again, 10 milliseconds apart, until the query ends.

```kotlin
suspend fun <T> cancellable(query: suspend () -> T): T = coroutineScope {
    // The result or failure is kept, not thrown, so that a query the cancel stops
    // ends this as cancelled, not as failed.
    val running = async(Dispatchers.IO) { runCatching { query() } }
    try {
        running.await().getOrThrow()
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            while (!running.isCompleted) {
                SqliteJs.cancelAll()
                delay(10)
            }
        }
        throw e
    }
}
```

Wrap only a query that may need stopping, and cancel its coroutine from your **Cancel** button:

```kotlin
class RunawayQuery(private val scope: CoroutineScope, private val products: ProductDao) {
    private var job: Job? = null

    /** Starts JavaScript that never ends. */
    fun start() {
        job = scope.launch {
            cancellable { products.spin() }
        }
    }

    /** Call this from the Cancel button. */
    fun cancel() {
        job?.cancel()
    }
}
```

`ProductDao` and its `spin()` are on the page [Set up a database with JavaScript functions](sqlite-js-app-database.html). In an activity, pass `lifecycleScope` as the scope.

Do not wrap every query in `cancellable()`. A coroutine can be cancelled for many reasons, such as the user leaving a screen, and each cancel would also interrupt the JavaScript of your other queries.

## Cancel in Java, with a thread

Java has no coroutines. This class does the same work as `cancellable()`. It runs the query on a thread of its own. Its `cancel()` calls `cancelAll()` again and again, 10 milliseconds apart, until the query has ended, and returns at once, so you can call it from a button.

```java
final class CancellableQuery<T> {
    private static final ExecutorService THREADS = Executors.newCachedThreadPool();

    private final Future<T> running;
    private volatile boolean cancelled;

    /** Starts the query on another thread. */
    CancellableQuery(Callable<T> query) {
        running = THREADS.submit(query);
    }

    /**
     * Waits until the query has ended, and returns its result. Throws
     * CancellationException if cancel() was called, so that a query the cancel
     * stops ends as cancelled, not as failed.
     */
    T get() throws ExecutionException, InterruptedException {
        try {
            T result = running.get();
            if (cancelled) {
                throw new CancellationException();
            }
            return result;
        } catch (ExecutionException e) {
            if (cancelled) {
                throw new CancellationException();
            }
            throw e;
        }
    }

    /** Stops the query's JavaScript. Returns at once. */
    void cancel() {
        cancelled = true;
        THREADS.execute(() -> {
            // Future.cancel() could not stop the JavaScript. Only cancelAll() can.
            while (!running.isDone()) {
                SqliteJs.cancelAll();
                try {
                    running.get(10, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // the query has not ended: cancel again
                } catch (ExecutionException | InterruptedException e) {
                    // the loop ends when the query has ended
                }
            }
        });
    }
}
```

Cancel it from your **Cancel** button:

```java
final class RunawayQuery {
    private final ProductDao products;
    private CancellableQuery<Integer> query;

    RunawayQuery(ProductDao products) {
        this.products = products;
    }

    /** Starts JavaScript that never ends. */
    void start() {
        query = new CancellableQuery<>(products::spin);
    }

    /** Call this from the Cancel button. */
    void cancel() {
        if (query != null) {
            query.cancel();
        }
    }
}
```

To use a query's result, call `get()` on a thread other than the main thread: it waits until the query has ended. As in Kotlin, use `CancellableQuery` only for a query that may need stopping.
