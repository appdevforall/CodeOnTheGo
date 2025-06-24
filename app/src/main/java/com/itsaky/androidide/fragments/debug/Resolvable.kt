package com.itsaky.androidide.fragments.debug

import com.itsaky.androidide.lsp.debug.IDebugAdapter
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
    suspend fun resolve(): T?
}

abstract class AbstractResolvable<T> : Resolvable<T> {

    private val _deferred = CompletableDeferred<T>()

    val deferred: Deferred<T>
        get() = _deferred

    @OptIn(ExperimentalCoroutinesApi::class)
    override val isResolved: Boolean
        get() = deferred.completedOrNull != null

    companion object {
        @JvmStatic
        protected val logger: Logger = LoggerFactory.getLogger(AbstractResolvable::class.java)
    }

    final override suspend fun resolve(): T? {
        val result = try {
            doResolve()?.also(_deferred::complete)
        } catch (err: Throwable) {
            logger.error("Resolution failure", err)
            _deferred.completeExceptionally(err)
            null
        }

        return result
    }

    protected abstract suspend fun doResolve(): T
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
) : AbstractResolvable<VariableDescriptor>(), Variable<T> by delegate, Resolvable<VariableDescriptor> {

    internal val deferredValue = CompletableDeferred<T?>()

    private var overriddenValue: String? = null

    /**
     * Whether the variable is resolved.
     */
    override val isResolved: Boolean
        get() = super.isResolved && deferredValue.completedOrNull != null

    override val resolved: VariableDescriptor
        get() = checkNotNull(deferred.completedOrNull) {
            "Variable is not resolved"
        }

    fun resolvedName() = deferred.completedOrNull?.name ?: ""

    fun resolvedTypeName() = deferred.completedOrNull?.typeName ?: ""

    fun resolvedValue() = deferredValue.getValue(
        defaultValue = null,
    )

    suspend fun updateRemoteValue(debugAdapter: IDebugAdapter, newValueStr: String): Boolean {
        overriddenValue = newValueStr
        val value = overriddenValue ?: return false
        return debugAdapter.setVariable(deferred.completedOrNull?.name ?: "", value)
    }

    companion object {

        private val logger = LoggerFactory.getLogger(ResolvableVariable::class.java)

        /**
         * Create a new eagerly-resolved variable delegating to [delegate].
         *
         * @param delegate The delegate variable.
         * @return The new eagerly-resolved variable.
         */
        fun <T : Value> create(delegate: Variable<T>): ResolvableVariable<T> {
            if (delegate is ResolvableVariable<T>) {
                return delegate
            }

            return ResolvableVariable(delegate)
        }
    }

    /**
     * Resolve the variable state.
     */
    override suspend fun doResolve(): VariableDescriptor = coroutineScope {
        logger.debug("Resolving variable: {}", delegate.descriptor().name)
        // NOTE:
        // Care must be taken to only resolve values which are absolutely needed
        // to render the UI. Resolution of values which are not immediately required must be deferred.

        val value = async {
            delegate.value()
        }

        val descriptor = delegate.descriptor()
        deferredValue.complete(value.await())
        return@coroutineScope descriptor
    }

    override suspend fun objectMembers(): Set<ResolvableVariable<*>> =
        delegate.objectMembers()
            .map { variable ->
                // members of an eagerly-resolved variable are also eagerly-resolved
                create(variable)
            }.toSet()

    override fun toString(): String {
        return overriddenValue ?: resolvedValue()?.toString() ?: "<unavailable>"
    }
}

/**
 * @author Akash Yadav
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolvableStackFrame private constructor(
    private val delegate: StackFrame,
) : AbstractResolvable<StackFrameDescriptor>(), StackFrame by delegate, Resolvable<StackFrameDescriptor> {

    override val resolved: StackFrameDescriptor
        get() = checkNotNull(deferred.completedOrNull) {
            "Stack frame is not resolved"
        }

    companion object {

        /**
         * Create a new eagerly-resolved stack frame delegating to [delegate].
         */
        fun create(delegate: StackFrame): ResolvableStackFrame {
            if (delegate is ResolvableStackFrame) {
                return delegate
            }

            return ResolvableStackFrame(delegate)
        }
    }

    override suspend fun doResolve(): StackFrameDescriptor {
        getVariables().forEach { it.resolve() }
        return delegate.descriptor()
    }

    override suspend fun getVariables(): List<ResolvableVariable<*>> =
        delegate.getVariables().map { variable -> ResolvableVariable.create(variable) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResolvableThreadInfo private constructor(
    private val delegate: ThreadInfo,
) : AbstractResolvable<ThreadDescriptor>(), ThreadInfo by delegate, Resolvable<ThreadDescriptor> {

    override val resolved: ThreadDescriptor
        get() = checkNotNull(deferred.completedOrNull) {
            "Thread is not resolved"
        }

    companion object {

        /**
         * Create a new eagerly-resolved thread info delegating to [delegate].
         */
        fun create(delegate: ThreadInfo): ResolvableThreadInfo {
            if (delegate is ResolvableThreadInfo) {
                return delegate
            }

            return ResolvableThreadInfo(delegate)
        }
    }

    override suspend fun doResolve(): ThreadDescriptor {
        getFrames().forEach { it.resolve() }
        return delegate.descriptor()
    }

    override suspend fun getFrames(): List<ResolvableStackFrame> =
        delegate.getFrames().map { frame -> ResolvableStackFrame.create(frame) }
}
