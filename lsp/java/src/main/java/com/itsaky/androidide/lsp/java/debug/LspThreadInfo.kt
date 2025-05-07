package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.java.debug.utils.booleanValue
import com.itsaky.androidide.lsp.java.debug.utils.byteValue
import com.itsaky.androidide.lsp.java.debug.utils.charValue
import com.itsaky.androidide.lsp.java.debug.utils.doubleValue
import com.itsaky.androidide.lsp.java.debug.utils.floatValue
import com.itsaky.androidide.lsp.java.debug.utils.intValue
import com.itsaky.androidide.lsp.java.debug.utils.longValue
import com.itsaky.androidide.lsp.java.debug.utils.shortValue
import com.sun.jdi.LocalVariable
import com.sun.jdi.StackFrame
import com.itsaky.androidide.lsp.debug.model.LocalVariable as LspLocalVariable
import com.itsaky.androidide.lsp.debug.model.StackFrame as LspStackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo as LspThreadInfo

class JavaLocalVariable(
    val frame: StackFrame,
    val variable: LocalVariable,
) : LspLocalVariable {

    companion object {
        private val BOOLEAN_VALUES = setOf("true", "false")
    }

    override val name: String
        get() = variable.name()

    override val type: String
        get() = variable.typeName()

    override fun getValue(): String =
        frame.getValue(variable).toString()

    override fun setValue(value: String) {
        val typeSignature = variable.signature()
        val vm = frame.virtualMachine()

        // TODO: Allow users to use references to existing variables instead of
        //    just constant primitive values
        val newValue = when (typeSignature[0]) {
            'Z' -> vm.booleanValue(value)
            'B' -> vm.byteValue(value)
            'C' -> vm.charValue(value)
            'S' -> vm.shortValue(value)
            'I' -> vm.intValue(value)
            'J' -> vm.longValue(value)
            'F' -> vm.floatValue(value)
            'D' -> vm.doubleValue(value)
            '[' -> TODO("Add support for array values")
            'L' -> TODO("Add support for object values")
            else -> throw IllegalArgumentException("Unsupported variable type: $typeSignature")
        }

        frame.setValue(variable, newValue)
    }
}

class JavaStackFrame(
    val frame: StackFrame,
) : LspStackFrame {

    override fun getVariables(): List<LspLocalVariable> =
        this.frame.visibleVariables().map { variable -> JavaLocalVariable(frame, variable) }

    override fun getVariableValues(): Map<LspLocalVariable, String> =
        getVariables().associateWith { variable -> variable.getValue() }

    override fun getValue(variable: LspLocalVariable): String =
        (variable as JavaLocalVariable).getValue()

    override fun setValue(
        variable: LspLocalVariable,
        value: String
    ) {
        (variable as JavaLocalVariable).setValue(value)
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