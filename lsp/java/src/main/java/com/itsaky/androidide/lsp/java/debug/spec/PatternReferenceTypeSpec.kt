package com.itsaky.androidide.lsp.java.debug.spec

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine

/**
 * @author Akash Yadav
 */
abstract class PatternReferenceTypeSpec(
    protected val candidate: String
) : ReferenceTypeSpec {

    protected open val stem = when {
        candidate.startsWith('*') -> candidate.substring(1)
        candidate.endsWith('*') -> candidate.substring(0, candidate.length - 1)
        else -> candidate
    }

    override fun matchingRefTypes(vm: VirtualMachine) = emptyList<ReferenceType>()

    /**
     * Is this a unique spec?
     */
    open val isUnique: Boolean
        get() = candidate == stem

    /**
     * Is this a class pattern?
     */
    open val isPattern: Boolean
        get() = !isUnique
}