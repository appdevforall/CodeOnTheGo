package com.itsaky.androidide.fragments.debug

import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A [variable][Variable] that is eagerly resolved.
 *
 * @author Akash Yadav
 */
class EagerVariable<T : Value> private constructor(
    private val delegate: Variable<T>,
) : Variable<T> by delegate {

    private val deferredName = CompletableDeferred<String>()
    private val deferredTypeName = CompletableDeferred<String>()
    private val deferredValue = CompletableDeferred<T?>()

    /**
     * Whether the variable is resolved.
     */
    val isResolved: Boolean
        get() = deferredName.isCompleted
                && deferredTypeName.isCompleted
                && deferredValue.isCompleted

    fun resolvedName() = deferredName.getValue(
        defaultValue = "...",
        errorValue = "<error>"
    )

    fun resolvedTypeName() = deferredTypeName.getValue(
        defaultValue = "...",
        errorValue = "<error>"
    )

    fun resolvedValue() = deferredValue.getValue(
        defaultValue = null,
        errorValue = null
    )

    companion object {

        /**
         * Create a new eagerly-resolved variable delegating to [delegate].
         *
         * @param delegate The delegate variable.
         * @return The new eagerly-resolved variable.
         */
        suspend fun <T : Value> create(delegate: Variable<T>): EagerVariable<T> {
            if (delegate is EagerVariable<T>) {
                return delegate
            }

            return coroutineScope {
                val variable = EagerVariable(delegate)

                // resolve in background
                launch { variable.resolve() }

                variable
            }
        }
    }

    /**
     * Resolve the variable state.
     */
    private suspend fun resolve() {
        // NOTE:
        // Care must be take to only resolve values which are absolutely needed
        // to render the UI. Resolution of values which are not immediately required must be deferred.

        deferredName.complete(delegate.name)
        deferredTypeName.complete(delegate.typeName)
        deferredValue.complete(delegate.value())
    }

    override suspend fun objectMembers(): Set<EagerVariable<*>> =
        delegate.objectMembers()
            .map { variable ->
                // members of an eagerly-resolved variable are also eagerly-resolved
                create(variable)
            }.toSet()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> CompletableDeferred<T>.getValue(
    defaultValue: T,
    errorValue: T
): T {
    if (!this.isCompleted) {
        return defaultValue
    }

    if (this.getCompletionExceptionOrNull() != null) {
        return errorValue
    }

    return this.getCompleted()
}

/**
 * @author Akash Yadav
 */
class EagerStackFrame private constructor(
    private val delegate: StackFrame,
) : StackFrame by delegate {

    companion object {

        /**
         * Create a new eagerly-resolved stack frame delegating to [delegate].
         */
        fun create(delegate: StackFrame): EagerStackFrame {
            if (delegate is EagerStackFrame) {
                return delegate
            }

            return EagerStackFrame(delegate)
        }
    }

    override suspend fun getVariables(): List<EagerVariable<*>> =
        delegate.getVariables().map { variable -> EagerVariable.create(variable) }
}

class EagerThreadInfo private constructor(
    private val delegate: ThreadInfo,
) : ThreadInfo by delegate {

    companion object {

        /**
         * Create a new eagerly-resolved thread info delegating to [delegate].
         */
        fun create(delegate: ThreadInfo): EagerThreadInfo {
            if (delegate is EagerThreadInfo) {
                return delegate
            }

            return EagerThreadInfo(delegate)
        }
    }

    override suspend fun getFrames(): List<EagerStackFrame> =
        delegate.getFrames().map { frame -> EagerStackFrame.create(frame) }
}
