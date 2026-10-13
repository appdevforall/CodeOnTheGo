package org.appdevforall.codeonthego.fragments.debug

import org.appdevforall.codeonthego.lsp.debug.model.StackFrameDescriptor
import org.appdevforall.codeonthego.lsp.debug.model.ThreadDescriptor

fun ThreadDescriptor.displayText(): String = toString()

fun StackFrameDescriptor.displayText(): String = method
