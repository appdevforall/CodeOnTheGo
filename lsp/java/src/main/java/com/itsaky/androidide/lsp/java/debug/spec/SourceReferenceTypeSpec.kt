package com.itsaky.androidide.lsp.java.debug.spec

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.ClassPrepareRequest

/**
 * @author Akash Yadav
 */
class SourceReferenceTypeSpec(
    private val source: Source,
) : ReferenceTypeSpec {

    override fun matches(refType: ReferenceType): Boolean {
        TODO("Not yet implemented")
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