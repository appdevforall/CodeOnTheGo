package com.itsaky.androidide.hiddenapis

import android.annotation.SuppressLint
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class HiddenApisBase {

	private val lock = ReentrantLock()

	var isInitialized = false
		private set

	protected fun initIfNeeded() = lock.withLock {
		if (!isInitialized) {
			HiddenApiBypass.setHiddenApiExemptions("")
			init()
		}
	}

	protected inline fun <R> initAndDo(action: () -> R): R {
		initIfNeeded()
		return action()
	}

	@SuppressLint("PrivateApi")
	protected abstract fun init()
}