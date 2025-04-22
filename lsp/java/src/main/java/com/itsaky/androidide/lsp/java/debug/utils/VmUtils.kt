package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.BreakpointRequest
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VmUtils")

fun VirtualMachine.relativePath(type: ReferenceType): String = try {
    type.sourcePaths(this.defaultStratum).firstOrNull() ?: ""
} catch (err: AbsentInformationException) {
    ""
}

fun VirtualMachine.isBreakpointInSource(br: BreakpointRequest, source: Source): Boolean = try {
    val relativePath = br.location().sourcePath(this.defaultStratum)
    source.path.endsWith(relativePath)
} catch (err: AbsentInformationException) {
    logger.warn("No source information for {}", br.location())
    false
}
