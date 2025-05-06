package com.itsaky.androidide.lsp.java.debug.spec

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.ClassPrepareRequest
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
class SourceReferenceTypeSpec(
    internal val source: Source,
) : ReferenceTypeSpec {

    companion object {
        private val logger = LoggerFactory.getLogger(SourceReferenceTypeSpec::class.java)
    }

    override fun matches(vm: VirtualMachine, refType: ReferenceType): Boolean {
        try {
            val sourcePath = refType.sourcePaths(vm.defaultStratum)
                .firstOrNull() ?: return false
            return this.source.path.endsWith(sourcePath)
        } catch (err: Exception) {
            // ignored
        }

        return false
    }

    override fun createPrepareRequest(vm: VirtualMachine): ClassPrepareRequest {
        val request = vm
            .eventRequestManager()
            .createClassPrepareRequest()
        request.addSourceNameFilter("*${source.name}")
        request.addCountFilter(1)
        return request
    }
}