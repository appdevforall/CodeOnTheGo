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
    if (!isCompleted) {
        return defaultValue
    }

    if (isCompleted && this.getCompletionExceptionOrNull() != null) {
        return defaultValue
    }

    return completedOrNull ?: defaultValue
}