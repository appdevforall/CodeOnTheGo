package com.itsaky.androidide.lsp.kotlin.compiler

import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker
import java.util.concurrent.atomic.AtomicLong

class IncrementalModificationTracker : ModificationTracker {

	private val myCounter = AtomicLong(0)

	/**
	 * Increment the modification count.
	 */
	fun incModificationCount() = apply {
		myCounter.incrementAndGet()
	}

	operator fun inc() = incModificationCount()

	override fun getModificationCount(): Long {
		return myCounter.get()
	}
}