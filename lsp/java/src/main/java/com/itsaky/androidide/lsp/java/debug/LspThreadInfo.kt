package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue
import com.itsaky.androidide.lsp.debug.model.StackFrameDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadState
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.itsaky.androidide.lsp.debug.model.StackFrame as LspStackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo as LspThreadInfo
import com.itsaky.androidide.lsp.debug.model.ThreadState as LspThreadState
import com.itsaky.androidide.lsp.debug.model.Variable as LspVariable

class JavaStackFrame(
    val frame: StackFrame,
) : LspStackFrame {

    private val logger = LoggerFactory.getLogger(JavaStackFrame::class.java)

    override suspend fun descriptor() = withContext(Dispatchers.IO) {
        val location = frame.location()
        val method = checkNotNull(location.method()) {
            "Method not found for location: $location"
        }

        StackFrameDescriptor(
            method = method.name(),
            methodSignature = method.signature(),
            sourceFile = location.sourceName(),
            lineNumber = location.lineNumber().toLong()
        )
    }

    override suspend fun getVariables(): List<LspVariable<*>> =
        JavaDebugAdapter.requireInstance().evalContext().evaluate(frame.thread()) {
            val method = frame.location().method()
            if (method == null || method.isAbstract || method.isNative) {
                // non-concrete method
                // does not have any variables
                return@evaluate emptyList()
            }

            val variables = frame.visibleVariables()
                // some opaque frames in core Android classes have empty variable names (like in ZygoteInit)
                .filter { it.name().isNotBlank() }

            return@evaluate variables.map { variable ->
                JavaLocalVariable.forVariable(
                    frame,
                    variable
                )
            }
        } ?: emptyList()

    override suspend fun <Val : Value> setValue(variable: Variable<Val>, value: Val) =
        withContext(Dispatchers.IO) {
            variable as JavaLocalVariable
            when (variable.kind) {
                VariableKind.PRIMITIVE -> {
                    check(value is PrimitiveValue) {
                        "Value $value is not a primitive value"
                    }

                    variable as JavaPrimitiveVariable
                    when (variable.primitiveKind) {
                        PrimitiveKind.BOOLEAN -> variable.doSetValue(value.asBoolean())
                        PrimitiveKind.BYTE -> variable.doSetValue(value.asByte())
                        PrimitiveKind.CHAR -> variable.doSetValue(value.asChar())
                        PrimitiveKind.SHORT -> variable.doSetValue(value.asShort())
                        PrimitiveKind.INT -> variable.doSetValue(value.asInt())
                        PrimitiveKind.LONG -> variable.doSetValue(value.asLong())
                        PrimitiveKind.FLOAT -> variable.doSetValue(value.asFloat())
                        PrimitiveKind.DOUBLE -> variable.doSetValue(value.asDouble())
                    }
                }

                // TODO: Support other types of variable values
                else -> throw IllegalStateException("Unsupported variable kind: ${variable.kind}")
            }
        }
}

internal class LspThreadInfo(
    val thread: ThreadInfo
) : LspThreadInfo {

    companion object {
        private val logger = LoggerFactory.getLogger(LspThreadInfo::class.java)
    }

    override suspend fun descriptor(): ThreadDescriptor = withContext(Dispatchers.IO) {
        val thread = thread.thread
        val group = thread.threadGroup()

        ThreadDescriptor(
            id = thread.uniqueID().toString(),
            name = thread.name(),
            group = group.name(),
            state = threadStateOf(thread.status())
        )
    }

    override suspend fun getFrames(): List<LspStackFrame> =
        JavaDebugAdapter.requireInstance().evalContext().evaluate(thread.thread) {
            thread.frames().map(::JavaStackFrame)
        } ?: emptyList()
}

private fun threadStateOf(state: Int) = when (state) {
    ThreadReference.THREAD_STATUS_UNKNOWN -> LspThreadState.UNKNOWN
    ThreadReference.THREAD_STATUS_ZOMBIE -> LspThreadState.ZOMBIE
    ThreadReference.THREAD_STATUS_RUNNING -> LspThreadState.RUNNING
    ThreadReference.THREAD_STATUS_SLEEPING -> LspThreadState.SLEEPING
    ThreadReference.THREAD_STATUS_MONITOR -> LspThreadState.MONITOR
    ThreadReference.THREAD_STATUS_WAIT -> LspThreadState.WAITING
    ThreadReference.THREAD_STATUS_NOT_STARTED -> LspThreadState.NOT_STARTED
    else -> ThreadState.UNKNOWN
}
