package com.itsaky.androidide.lsp.java.debug.spec

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine

/**
 * @author Akash Yadav
 */
internal object DebugEnv {

    fun getReferenceTypeFromToken(vm: VirtualMachine, token: String): ReferenceType? {
        var idToken = token
        var cls: ReferenceType? = null
        if (Character.isDigit(idToken[0])) {
            cls = null
        } else if (idToken.startsWith("*.")) {
            // This notation saves typing by letting the user omit leading
            // package names. The first
            // loaded class whose name matches this limited regular
            // expression is selected.
            idToken = idToken.substring(1)
            for (type in vm.allClasses()) {
                if (type.name().endsWith(idToken)) {
                    cls = type
                    break
                }
            }
        } else {
            // It's a class name
            val classes: List<ReferenceType> = vm.classesByName(idToken)
            if (classes.isNotEmpty()) {
                // TODO: handle multiples
                cls = classes[0]
            }
        }
        return cls
    }
}