package com.itsaky.androidide.lsp.java.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
val <T> CompletableDeferred<T>.completedOrNull: T?
    get() = if (isCompleted && getCompletionExceptionOrNull() == null) getCompleted() else null

@ExperimentalCoroutinesApi
fun <T> CompletableDeferred<T>.getValue(
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