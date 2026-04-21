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