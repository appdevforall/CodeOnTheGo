package com.itsaky.androidide.lsp.java.debug.spec

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