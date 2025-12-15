package com.itsaky.androidide.fragments.debug

import com.itsaky.androidide.lsp.debug.model.StackFrameDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor

fun ThreadDescriptor.displayText(): String = toString()

fun StackFrameDescriptor.displayText(): String = method
