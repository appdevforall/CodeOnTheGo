package com.itsaky.androidide.lsp.java.debug.spec

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.ClassPrepareRequest

/**
 * @author Akash Yadav
 */
interface ReferenceTypeSpec {

    /**
     * Whether this spec matches the given reference type.
     */
    fun matches(vm: VirtualMachine, refType: ReferenceType): Boolean

    /**
     * Creates a [ClassPrepareRequest] for this spec.
     */
    fun createPrepareRequest(vm: VirtualMachine): ClassPrepareRequest
}