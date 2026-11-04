package com.itsaky.androidide.lsp.kotlin.compiler

import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val key = Key.create<ReentrantReadWriteLock>("org.adfa.cotg.rwlock")
private val lock = ReentrantReadWriteLock()

fun Project.registerRWLock() {
	putUserData(key, lock)
}

fun <T> Project.read(fn: () -> T): T {
	val lock = getUserData(key)!!
	return lock.read(fn)
}

fun <T> Project.write(fn: () -> T): T {
	val lock = getUserData(key)!!
	return lock.write(fn)
}

/**
 * Runs [fn] under the read lock and returns its result, or returns null without running it if the project is disposed.
 *
 * Disposal takes the write lock, so the project cannot be disposed while [fn] runs.
 */
fun <T> Project.readUnlessDisposed(fn: () -> T): T? = read { if (isDisposed) null else fn() }

/** Runs [fn] under the write lock and returns its result, or returns null without running it if the project is disposed. */
fun <T> Project.writeUnlessDisposed(fn: () -> T): T? = write { if (isDisposed) null else fn() }

/**
 * Runs [fn] under the write lock if it is free now, without waiting or queueing; returns whether it ran.
 *
 * A queued writer would block every new reader, including the UI thread, until it got the lock.
 */
fun Project.tryWrite(fn: () -> Unit): Boolean {
	val writeLock = getUserData(key)!!.writeLock()
	if (!writeLock.tryLock()) return false
	try {
		fn()
	} finally {
		writeLock.unlock()
	}
	return true
}
