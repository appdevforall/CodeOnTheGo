package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.LocalVariableValue
import com.sun.jdi.LocalVariable
import com.sun.jdi.StackFrame
import com.itsaky.androidide.lsp.debug.model.LocalVariable as LspLocalVariable
import com.itsaky.androidide.lsp.debug.model.StackFrame as LspStackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo as LspThreadInfo

class JavaLocalVariable(
    val frame: StackFrame,
    val variable: LocalVariable,
) : LspLocalVariable {
    override val name: String
        get() = variable.name()

    override val type: String
        get() = variable.typeName()

    override fun getValue(): LocalVariableValue =
        object : LocalVariableValue {
            override fun toString(): String =
                frame.getValue(variable).toString()
        }

    override fun setValue(value: LocalVariableValue) {
        TODO("Not yet implemented")
    }
}

class JavaStackFrame(
    val frame: StackFrame,
) : LspStackFrame {

    override fun getVariables(): List<LspLocalVariable> =
        this.frame.visibleVariables().map { variable -> JavaLocalVariable(frame, variable) }

    override fun getVariableValues(): Map<LspLocalVariable, LocalVariableValue> =
        getVariables().associateWith { variable -> variable.getValue() }

    override fun getValue(variable: LspLocalVariable): LocalVariableValue =
        (variable as JavaLocalVariable).getValue()

    override fun setValue(
        variable: LspLocalVariable,
        value: LocalVariableValue
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