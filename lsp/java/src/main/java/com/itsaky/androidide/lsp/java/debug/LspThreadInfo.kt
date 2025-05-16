package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue
import com.itsaky.androidide.lsp.debug.model.StringValue
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.sun.jdi.StackFrame
import com.itsaky.androidide.lsp.debug.model.StackFrame as LspStackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo as LspThreadInfo
import com.itsaky.androidide.lsp.debug.model.Variable as LspVariable

class JavaStackFrame(
    val frame: StackFrame,
) : LspStackFrame {

    override fun getVariables(): List<LspVariable<*>> =
        this.frame.visibleVariables().map { variable -> JavaVariable.forVariable(frame, variable) }

    override fun <Val : Value> setValue(variable: Variable<Val>, value: Val) {
        variable as JavaVariable
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

    override fun getName(): String =
        thread.thread.name()

    override fun getFrames(): List<LspStackFrame> =
        thread.frames()
            .map(::JavaStackFrame)
}