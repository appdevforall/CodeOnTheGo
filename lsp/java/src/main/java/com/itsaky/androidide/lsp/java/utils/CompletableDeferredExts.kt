package com.itsaky.androidide.lsp.java.utils

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
val <T> Deferred<T>.completedOrNull: T?
    get() = if (isCompleted && getCompletionExceptionOrNull() == null) getCompleted() else null

@ExperimentalCoroutinesApi
fun <T> Deferred<T>.getValue(
    defaultValue: T,
): T {
    val alreadyCompleted = completedOrNull
    if (alreadyCompleted != null) {
        return alreadyCompleted
    }

    if (this.getCompletionExceptionOrNull() != null) {
        return defaultValue
    }

    return this.getCompleted()
}