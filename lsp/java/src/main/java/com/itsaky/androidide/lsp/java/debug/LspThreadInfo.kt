package com.itsaky.androidide.lsp.java.debug

import com.sun.jdi.LocalVariable
import com.sun.jdi.StackFrame
import com.itsaky.androidide.lsp.debug.events.LocalVariable as LspLocalVariable
import com.itsaky.androidide.lsp.debug.events.StackFrame as LspStackFrame
import com.itsaky.androidide.lsp.debug.events.ThreadInfo as LspThreadInfo

class JavaLocalVariable(
    val frame: StackFrame,
    val variable: LocalVariable,
): LspLocalVariable {
    override val name: String
        get() = variable.name()

    override val type: String
        get() = variable.typeName()

    override fun getValue(): String =
        frame.getValue(variable).toString()
}

class JavaStackFrame(
    val frame: StackFrame,
): LspStackFrame {

    override fun getVariables(): List<LspLocalVariable> =
        this.frame.visibleVariables().map { variable -> JavaLocalVariable(frame, variable) }

    override fun getVariableValues(): Map<LspLocalVariable, String> =
        getVariables().associateWith { variable -> variable.getValue() }

    override fun getValue(variable: LspLocalVariable): String =
        (variable as JavaLocalVariable).getValue()
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