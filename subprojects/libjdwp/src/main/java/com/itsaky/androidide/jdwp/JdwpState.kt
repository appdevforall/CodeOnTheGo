package com.itsaky.androidide.jdwp

import com.sun.jdi.Location
import com.sun.jdi.VirtualMachine
import com.sun.jdi.VirtualMachineManager

/**
 * Connection state of [JdwpDebugger].
 *
 * @author Akash Yadav
 */
data class JdwpState(
    val vmm: VirtualMachineManager,
    val vm: VirtualMachine,
    val breakpoints: List<Location>,
)
