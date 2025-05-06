package com.itsaky.androidide.lsp.java.debug.spec

import com.itsaky.androidide.lsp.java.debug.utils.isJavaIdentifier
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.ClassPrepareRequest
import java.util.StringTokenizer

/**
 * A [ReferenceTypeSpec] which allows pattern matching for class names.
 *
 * @author Akash Yadav
 */
class ClassPatternReferenceTypeSpec(
    classId: String,
) : PatternReferenceTypeSpec(classId) {

    init {
        checkClassName(stem)
    }

    override fun matches(vm: VirtualMachine, refType: ReferenceType): Boolean {
        return when {
            candidate.startsWith("*") -> refType.name().endsWith(stem)
            candidate.endsWith("*") -> refType.name().startsWith(stem)
            else -> refType.name().equals(candidate)
        }
    }

    override fun createPrepareRequest(vm: VirtualMachine): ClassPrepareRequest {
        val request = vm
            .eventRequestManager()
            .createClassPrepareRequest()
        request.addClassFilter(candidate)
        request.addCountFilter(1)
        return request
    }

    /**
     * @see <a href="https://github.com/itsaky/openjdk-21-android/blob/main/src/jdk.jdi/share/classes/com/sun/tools/example/debug/tty/PatternReferenceTypeSpec.java">PatternReferenceTypeSpec.java</a>
     */
    @Throws(ClassNotFoundException::class)
    private fun checkClassName(name: String) {
        var className = name
        val slashIdx = className.indexOf("/")

        // Slash is present in hidden class names only. It looks like p.Foo/0x1234.
        if (slashIdx != -1) {
            // A hidden class name is ending with a slash following by a suffix.
            val lastSlashIdx = className.lastIndexOf("/")
            val lastDotIdx = className.lastIndexOf(".")

            // There must be just one slash with a following suffix but no dots.
            if (slashIdx != lastSlashIdx || lastDotIdx > slashIdx || slashIdx + 1 == className.length) {
                throw ClassNotFoundException()
            }
            // Check prefix and suffix separately.
            val parts = className.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            assert(parts.size == 2)
            className = parts[0]
            val hcSuffix = parts[1]
            if (!isUnqualifiedName(hcSuffix)) {
                throw ClassNotFoundException()
            }
        }

        // Do stricter checking of class name validity on deferred
        //  because if the name is invalid, it will
        // never match a future loaded class, and we'll be silent
        // about it.
        val tokenizer = StringTokenizer(className, ".")
        while (tokenizer.hasMoreTokens()) {
            val token = tokenizer.nextToken()
            // Each dot-separated piece must be a valid identifier
            // and the first token can also be "*". (Note that
            // numeric class ids are not permitted. They must
            // match a loaded class.)
            if (!token.isJavaIdentifier()) {
                throw ClassNotFoundException()
            }
        }
    }

    private fun isUnqualifiedName(s: String): Boolean {
        if (s.isEmpty()) {
            return false
        }

        // unqualified names should have no characters: ".;/["
        return !s.matches("[.;/\u000091]*".toRegex()) // \091 is '['
    }
}