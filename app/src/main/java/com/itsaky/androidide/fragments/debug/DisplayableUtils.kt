package com.itsaky.androidide.fragments.debug

import com.itsaky.androidide.lsp.debug.model.StackFrameDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor

fun ThreadDescriptor.displayText(): String =
    "'${name}'@${id} in group '${group}': ${state.uppercase()}"

fun StackFrameDescriptor.displayText(): String =
    "${sourceFile}:${lineNumber} in ${method}${methodSignature}"