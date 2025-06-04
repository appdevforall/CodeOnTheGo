package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue
import com.itsaky.androidide.lsp.debug.model.StackFrameDescriptor
import com.itsaky.androidide.lsp.debug.model.StringValue
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.sun.jdi.StackFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.itsaky.androidide.lsp.debug.model.StackFrame as LspStackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo as LspThreadInfo
import com.itsaky.androidide.lsp.debug.model.Variable as LspVariable

class JavaStackFrame(
    val frame: StackFrame,
) : LspStackFrame {

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

    override suspend fun getVariables(): List<LspVariable<*>> {
        val variables = withContext(Dispatchers.IO) { frame.visibleVariables() }
        return variables.map { variable -> JavaLocalVariable.forVariable(frame, variable) }
    }

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
                        PrimitiveKind.BOOLEAN -> variable.setValue(value.asBoolean())
                        PrimitiveKind.BYTE -> variable.setValue(value.asByte())
                        PrimitiveKind.CHAR -> variable.setValue(value.asChar())
                        PrimitiveKind.SHORT -> variable.setValue(value.asShort())
                        PrimitiveKind.INT -> variable.setValue(value.asInt())
                        PrimitiveKind.LONG -> variable.setValue(value.asLong())
                        PrimitiveKind.FLOAT -> variable.setValue(value.asFloat())
                        PrimitiveKind.DOUBLE -> variable.setValue(value.asDouble())
                    }
                }

                VariableKind.STRING -> {
                    check(value is StringValue) {
                        "Value $value is not a string value"
                    }

                    variable as JavaStringVariable
                    variable.setValue(value.asString())
                }

                // TODO: Support other types of variable values
                else -> throw IllegalStateException("Unsupported variable kind: ${variable.kind}")
            }
        }
}

internal class LspThreadInfo(
    val thread: ThreadInfo
) : LspThreadInfo {

    override suspend fun descriptor(): ThreadDescriptor = withContext(Dispatchers.IO) {
        val thread = thread.thread
        val group = thread.threadGroup()

        ThreadDescriptor(
            id = thread.uniqueID().toString(),
            name = thread.name(),
            group = group.name(),
            state = thread.status().toString()
        )
    }

    override suspend fun getFrames(): List<LspStackFrame> =
        thread.frames()
            .map(::JavaStackFrame)
}