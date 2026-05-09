@file:Suppress("UnstableApiUsage")

package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.concurrency.Promise
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.application.AppUIExecutor
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.application.AsyncExecutionService
import org.jetbrains.kotlin.com.intellij.openapi.application.ExpirableExecutor
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState
import org.jetbrains.kotlin.com.intellij.openapi.application.NonBlockingReadAction
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.util.Function
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/**
 * No-op [AsyncExecutionService] for standalone (non-IDE) environments.
 *
 * The real implementation requires an IDE event-dispatch / write-thread infrastructure that does
 * not exist in our standalone setup. Submitted tasks are run asynchronously on the common
 * ForkJoin pool so that stub rebuilds triggered by structural PSI changes don't block the
 * analysis thread or deadlock against the project read/write lock.
 */
internal class NoOpAsyncExecutionService : AsyncExecutionService() {

    private val executor: AppUIExecutor = NoOpAppUIExecutor()

    override fun createExecutor(backgroundExecutor: Executor): ExpirableExecutor =
        NoOpExpirableExecutor(backgroundExecutor)

    override fun createUIExecutor(modalityState: ModalityState): AppUIExecutor = executor

    override fun createWriteThreadExecutor(modalityState: ModalityState): AppUIExecutor = executor

    override fun <T> buildNonBlockingReadAction(callable: Callable<out T>): NonBlockingReadAction<T> =
        NoOpNonBlockingReadAction(callable)
}

private class NoOpAppUIExecutor : AppUIExecutor {
    override fun later(): AppUIExecutor = this
    override fun withDocumentsCommitted(project: Project): AppUIExecutor = this
    override fun inSmartMode(project: Project): AppUIExecutor = this
    override fun expireWith(disposable: Disposable): AppUIExecutor = this

    override fun execute(runnable: Runnable) {
        ApplicationManager.getApplication().invokeLater(runnable)
    }

    override fun <T> submit(callable: Callable<T>): CancellablePromise<T> {
        val future = CompletableFuture<T>()
        ApplicationManager.getApplication().invokeLater {
            try { future.complete(callable.call()) }
            catch (e: Throwable) { future.completeExceptionally(e) }
        }
        return future.asCancellablePromise()
    }

    override fun submit(runnable: Runnable): CancellablePromise<*> {
        val future = CompletableFuture<Any?>()
        ApplicationManager.getApplication().invokeLater {
            try { runnable.run(); future.complete(null) }
            catch (e: Throwable) { future.completeExceptionally(e) }
        }
        return future.asCancellablePromise()
    }
}

private class NoOpExpirableExecutor(private val exec: Executor) : ExpirableExecutor {
    override fun expireWith(disposable: Disposable): ExpirableExecutor = this

    override fun execute(runnable: Runnable) = exec.execute(runnable)

    override fun <T> submit(callable: Callable<T>): CancellablePromise<T> =
        CompletableFuture.supplyAsync({ callable.call() }, exec).asCancellablePromise()

    override fun submit(runnable: Runnable): CancellablePromise<*> =
        CompletableFuture.runAsync(runnable, exec).thenApply<Any?> { null }.asCancellablePromise()
}

private class NoOpNonBlockingReadAction<T>(private val callable: Callable<out T>) : NonBlockingReadAction<T> {
    override fun inSmartMode(project: Project): NonBlockingReadAction<T> = this
    override fun withDocumentsCommitted(project: Project): NonBlockingReadAction<T> = this
    override fun expireWhen(condition: BooleanSupplier): NonBlockingReadAction<T> = this
    override fun wrapProgress(indicator: ProgressIndicator): NonBlockingReadAction<T> = this
    override fun expireWith(disposable: Disposable): NonBlockingReadAction<T> = this
    override fun finishOnUiThread(modalityState: ModalityState, uiThreadAction: Consumer<in T>): NonBlockingReadAction<T> = this
    override fun coalesceBy(vararg equality: Any): NonBlockingReadAction<T> = this

    override fun submit(backgroundThreadExecutor: Executor): CancellablePromise<T> =
        CompletableFuture.supplyAsync({ callable.call() }, backgroundThreadExecutor)
            .asCancellablePromise()

    override fun executeSynchronously(): T = callable.call()
}

private fun <T> CompletableFuture<T>.asCancellablePromise(): CancellablePromise<T> =
    CompletableFutureCancellablePromise(this)

private class CompletableFutureCancellablePromise<T>(
    private val future: CompletableFuture<T>
) : CancellablePromise<T> {

    // Future
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = future.cancel(mayInterruptIfRunning)
    override fun isCancelled(): Boolean = future.isCancelled
    override fun isDone(): Boolean = future.isDone
    override fun get(): T = future.get()
    override fun get(timeout: Long, unit: TimeUnit): T = future.get(timeout, unit)

    // CancellablePromise
    override fun cancel() { future.cancel(true) }

    override fun onSuccess(handler: Consumer<in T>): CancellablePromise<T> {
        future.thenAccept(handler)
        return this
    }

    override fun onError(handler: Consumer<in Throwable>): CancellablePromise<T> {
        future.exceptionally { e -> handler.accept(e); null }
        return this
    }

	override fun onProcessed(handler: Consumer<in T?>): CancellablePromise<T> {
		future.whenComplete { value, _ -> handler.accept(value) }
		return this
	}

    // Promise
    override fun getState(): Promise.State = when {
        future.isCancelled || future.isCompletedExceptionally -> Promise.State.REJECTED
        future.isDone -> Promise.State.SUCCEEDED
        else -> Promise.State.PENDING
    }

    override fun processed(child: Promise<in T>): Promise<T> = this

    override fun blockingGet(timeout: Int, unit: TimeUnit): T = future.get(timeout.toLong(), unit)

    override fun <SUB_RESULT> then(handler: Function<in T, out SUB_RESULT>): Promise<SUB_RESULT> =
        future.thenApply { handler.`fun`(it) }.asCancellablePromise()

    override fun <SUB_RESULT> thenAsync(
        handler: Function<in T, out Promise<SUB_RESULT>>
    ): Promise<SUB_RESULT> = future.thenCompose { value ->
        @Suppress("UNCHECKED_CAST")
        (handler.`fun`(value) as CompletableFutureCancellablePromise<SUB_RESULT>).future
    }.asCancellablePromise()
}
