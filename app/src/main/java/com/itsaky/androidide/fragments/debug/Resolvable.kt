package com.itsaky.androidide.fragments.debug

import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.lsp.debug.model.StackFrameDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableDescriptor
import com.itsaky.androidide.lsp.java.utils.completedOrNull
import com.itsaky.androidide.lsp.java.utils.getValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

interface Resolvable<T> {

    /**
     * Whether the value is resolved.
     */
    val isResolved: Boolean

    /**
     * Get the resolved value, or throw an exception if the value is not resolved.
     */
    val resolved: T

    /**
     * Resolve the value.
     */
    suspend fun resolve(): T
}

/**
 * Get the resolved value, or return `null` if the value is not resolved.
 */
val <T> Resolvable<T>.resolvedOrNull: T?
    get() = if (isResolved) resolved else null

/**
 * Get the resolved value, or return [default value][default] if the value is not resolved.
 *
 * @param default The default value to return if the value is not resolved.
 */
fun <T> Resolvable<T>.resolvedOr(default: T): T? = if (isResolved) resolved else default

/**
 * A [variable][Variable] that is eagerly resolved.
 *
 * @author Akash Yadav
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolvableVariable<T : Value> private constructor(
    private val delegate: Variable<T>,
) : Variable<T> by delegate, Resolvable<VariableDescriptor> {

    private val deferredDescriptor = CompletableDeferred<VariableDescriptor>()
    private val deferredValue = CompletableDeferred<T?>()

    /**
     * Whether the variable is resolved.
     */
    override val isResolved: Boolean
        get() = deferredDescriptor.completedOrNull != null
                && deferredValue.completedOrNull != null

    override val resolved: VariableDescriptor
        get() = checkNotNull(deferredDescriptor.completedOrNull) {
            "Variable is not resolved"
        }

    fun resolvedName() = deferredDescriptor.completedOrNull?.name ?: ""

    fun resolvedTypeName() = deferredDescriptor.completedOrNull?.typeName ?: ""

    fun resolvedValue() = deferredValue.getValue(
        defaultValue = null,
    )

    companion object {

        /**
         * Create a new eagerly-resolved variable delegating to [delegate].
         *
         * @param delegate The delegate variable.
         * @return The new eagerly-resolved variable.
         */
        suspend fun <T : Value> create(delegate: Variable<T>): ResolvableVariable<T> {
            if (delegate is ResolvableVariable<T>) {
                return delegate
            }

            return ResolvableVariable(delegate).also { variable ->
                variable.resolve()
            }
        }
    }

    /**
     * Resolve the variable state.
     */
    override suspend fun resolve(): VariableDescriptor = coroutineScope {
        // NOTE:
        // Care must be take to only resolve values which are absolutely needed
        // to render the UI. Resolution of values which are not immediately required must be deferred.

        val value = async { delegate.value() }
        val descriptor = delegate.descriptor()

        deferredDescriptor.complete(descriptor)
        deferredValue.complete(value.await())
        return@coroutineScope descriptor
    }

    override suspend fun objectMembers(): Set<ResolvableVariable<*>> =
        delegate.objectMembers()
            .map { variable ->
                // members of an eagerly-resolved variable are also eagerly-resolved
                create(variable)
            }.toSet()
}

/**
 * @author Akash Yadav
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolvableStackFrame private constructor(
    private val delegate: StackFrame,
) : StackFrame by delegate, Resolvable<StackFrameDescriptor> {

    private val deferredDescriptor = CompletableDeferred<StackFrameDescriptor>()

    override val isResolved: Boolean
        get() = deferredDescriptor.completedOrNull != null

    override val resolved: StackFrameDescriptor
        get() = checkNotNull(deferredDescriptor.completedOrNull) {
            "Stack frame is not resolved"
        }

    companion object {

        /**
         * Create a new eagerly-resolved stack frame delegating to [delegate].
         */
        suspend fun create(delegate: StackFrame): ResolvableStackFrame {
            if (delegate is ResolvableStackFrame) {
                return delegate
            }

            return ResolvableStackFrame(delegate).also {
                it.resolve()
            }
        }
    }

    override suspend fun resolve(): StackFrameDescriptor {
        val descriptor = delegate.descriptor()
        deferredDescriptor.complete(descriptor)
        return descriptor
    }

    override suspend fun getVariables(): List<ResolvableVariable<*>> =
        delegate.getVariables().map { variable -> ResolvableVariable.create(variable) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResolvableThreadInfo private constructor(
    private val delegate: ThreadInfo,
) : ThreadInfo by delegate, Resolvable<ThreadDescriptor> {

    private val deferredDescriptor = CompletableDeferred<ThreadDescriptor>()

    override val isResolved: Boolean
        get() = deferredDescriptor.completedOrNull != null

    override val resolved: ThreadDescriptor
        get() = checkNotNull(deferredDescriptor.completedOrNull) {
            "Thread is not resolved"
        }

    companion object {

        /**
         * Create a new eagerly-resolved thread info delegating to [delegate].
         */
        suspend fun create(delegate: ThreadInfo): ResolvableThreadInfo {
            if (delegate is ResolvableThreadInfo) {
                return delegate
            }

            return ResolvableThreadInfo(delegate).also {
                it.resolve()
            }
        }
    }

    override suspend fun resolve(): ThreadDescriptor {
        val descriptor = delegate.descriptor()
        deferredDescriptor.complete(descriptor)
        return descriptor
    }

    override suspend fun getFrames(): List<ResolvableStackFrame> =
        delegate.getFrames().map { frame -> ResolvableStackFrame.create(frame) }
}
