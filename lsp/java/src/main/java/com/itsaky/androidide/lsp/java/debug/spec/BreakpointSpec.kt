package com.itsaky.androidide.lsp.java.debug.spec

import com.itsaky.androidide.utils.StringUtil.isJavaIdentifier
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.EventRequest


sealed interface BreakpointData {
    data class Global(
        val lineNumber: Int,
        val threadFilter: ThreadReference,
    ) : BreakpointData

    data class Method(
        val methodID: String,
        val methodArgs: List<String>,
        val threadFilter: ThreadReference,
    ) : BreakpointData
}

/**
 * @author Akash Yadav
 */
internal class BreakpointSpec : EventRequestSpec {

    val data: BreakpointData

    /**
     * Whether this breakpoint spec is a method breakpoint.
     */
    val isMethodBreakpoint: Boolean
        get() = data is BreakpointData.Method

    val lineNumber: Int
        get() = (data as? BreakpointData.Global?)?.lineNumber ?: 0

    constructor(refSpec: ReferenceTypeSpec, lineNumber: Int, threadFilter: ThreadReference) : super(
        refSpec
    ) {
        this.data = BreakpointData.Global(lineNumber, threadFilter)
    }

    constructor(
        refSpec: ReferenceTypeSpec,
        methodID: String,
        methodArgs: List<String>,
        threadFilter: ThreadReference
    ) : super(refSpec) {
        this.data = BreakpointData.Method(methodID, methodArgs, threadFilter)
    }

    override fun resolveEventRequest(
        vm: VirtualMachine,
        referenceType: ReferenceType
    ): EventRequest {
        TODO("Not yet implemented")
    }

    @Throws(
        AmbiguousMethodException::class,
        AbsentInformationException::class,
        NoSuchMethodException::class,
        LineNotFoundException::class
    )

    private fun location(vm: VirtualMachine, refType: ReferenceType): Location? {
        val location: Location?
        if (isMethodBreakpoint) {
            val method = findMatchingMethod(vm, refType, this.data as BreakpointData.Method)
            location = method?.location()
        } else {
            // let AbsentInformationException be thrown
            val locs: List<Location> = refType.locationsOfLine(lineNumber)
            if (locs.isEmpty()) {
                throw LineNotFoundException()
            }

            // TODO: handle multiple locations
            location = locs[0]
            if (location.method() == null) {
                throw LineNotFoundException()
            }
        }
        return location
    }

    private fun isValidMethodName(s: String): Boolean {
        return isJavaIdentifier(s) ||
                s == "<init>" ||
                s == "<clinit>"
    }

    /*
     * Compare a method's argument types with a Vector of type names.
     * Return true if each argument type has a name identical to the
     * corresponding string in the vector (allowing for varars)
     * and if the number of arguments in the method matches the
     * number of names passed
     */
    private fun compareArgTypes(method: Method, nameList: List<String>): Boolean {
        val argTypeNames: List<String> = method.argumentTypeNames()

        // If argument counts differ, we can stop here
        if (argTypeNames.size != nameList.size) {
            return false
        }

        // Compare each argument type's name
        val nTypes = argTypeNames.size
        for (i in 0..<nTypes) {
            val comp1 = argTypeNames[i]
            val comp2 = nameList[i]
            if (comp1 != comp2) {
                /*
                 * We have to handle varargs.  EG, the
                 * method's last arg type is xxx[]
                 * while the nameList contains xxx...
                 * Note that the nameList can also contain
                 * xxx[] in which case we don't get here.
                 */
                if (i != nTypes - 1 || !method.isVarArgs || !comp2.endsWith("...")) {
                    return false
                }
                /*
                 * The last types differ, it is a varargs
                 * method and the nameList item is varargs.
                 * We just have to compare the type names, eg,
                 * make sure we don't have xxx[] for the method
                 * arg type and yyy... for the nameList item.
                 */
                val comp1Length = comp1.length
                if (comp1Length + 1 != comp2.length) {
                    // The type names are different lengths
                    return false
                }
                // We know the two type names are the same length
                if (!comp1.regionMatches(0, comp2, 0, comp1Length - 2)) {
                    return false
                }
                // We do have xxx[] and xxx... as the last param type
                return true
            }
        }

        return true
    }


    /*
     * Remove unneeded spaces and expand class names to fully
     * qualified names, if necessary and possible.
     */
    private fun normalizeArgTypeName(vm: VirtualMachine, typeName: String): String {
        /*
         * Separate the type name from any array modifiers,
         * stripping whitespace after the name ends
         */
        var name = typeName
        var i = 0
        val typePart = StringBuilder()
        val arrayPart = StringBuilder()
        name = name.trim { it <= ' ' }
        var nameLength = name.length
        /*
         * For varargs, there can be spaces before the ... but not
         * within the ...  So, we will just ignore the ...
         * while stripping blanks.
         */
        val isVarArgs = name.endsWith("...")
        if (isVarArgs) {
            nameLength -= 3
        }
        while (i < nameLength) {
            val c = name[i]
            if (Character.isWhitespace(c) || c == '[') {
                break // name is complete
            }
            typePart.append(c)
            i++
        }
        while (i < nameLength) {
            val c = name[i]
            if ((c == '[') || (c == ']')) {
                arrayPart.append(c)
            } else if (!Character.isWhitespace(c)) {
                throw IllegalArgumentException("Invalid argument type name")
            }
            i++
        }
        name = typePart.toString()

        /*
         * When there's no sign of a package name already, try to expand the
         * the name to a fully qualified class name
         */
        if ((name.indexOf('.') == -1) || name.startsWith("*.")) {
            try {
                val argClass = DebugEnv.getReferenceTypeFromToken(vm, name)
                if (argClass != null) {
                    name = argClass.name()
                }
            } catch (e: IllegalArgumentException) {
                // We'll try the name as is
            }
        }
        name += arrayPart.toString()
        if (isVarArgs) {
            name += "..."
        }
        return name
    }

    /*
     * Attempt an unambiguous match of the method name and
     * argument specification to a method. If no arguments
     * are specified, the method must not be overloaded.
     * Otherwise, the argument types much match exactly
     */
    @Throws(AmbiguousMethodException::class, NoSuchMethodException::class)
    private fun findMatchingMethod(
        vm: VirtualMachine,
        refType: ReferenceType,
        data: BreakpointData.Method
    ): Method? {
        // Normalize the argument string once before looping below.
        val argTypeNames: MutableList<String>?
        argTypeNames = ArrayList(data.methodArgs.size)
        for (name in data.methodArgs) {
            argTypeNames.add(normalizeArgTypeName(vm, name))
        }

        // Check each method in the class for matches
        var firstMatch: Method? = null // first method with matching name
        var exactMatch: Method? = null // (only) method with same name & sig
        var matchCount = 0 // > 1 implies overload
        for (candidate in refType.methods()) {
            if (candidate.name().equals(data.methodID)) {
                matchCount++

                // Remember the first match in case it is the only one
                if (matchCount == 1) {
                    firstMatch = candidate
                }

                // If argument types were specified, check against candidate
                if (compareArgTypes(candidate, argTypeNames)) {
                    exactMatch = candidate
                    break
                }
            }
        }

        // Determine method for breakpoint
        var method: Method? = null
        method = // Name and signature match
            exactMatch ?: throw NoSuchMethodException(data.methodID)

        return method
    }
}