package com.itsaky.androidide.lsp.java.debug.spec

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest

/**
 * @author Akash Yadav
 */
internal abstract class EventRequestSpec(
    val refSpec: ReferenceTypeSpec,
    var suspendPolicy: Int = EventRequest.SUSPEND_ALL,
) {
    var resolved: EventRequest? = null
    var prepareRequest: ClassPrepareRequest? = null

    val isResolved: Boolean
        get() = resolved != null

    /**
     * Resolve the [EventRequest] for this spec.
     */
    abstract fun resolveEventRequest(vm: VirtualMachine, refType: ReferenceType): EventRequest

    /**
     * Resolve the [EventRequest] for this spec.
     *
     * @return The resolved [EventRequest], or `null` if the spec cannot be resolved.
     */
    @Synchronized
    fun resolve(vm: VirtualMachine, event: ClassPrepareEvent): EventRequest? {
        if (resolved == null && prepareRequest != null && prepareRequest == event.request()) {
            val resolved = resolveEventRequest(vm, event.referenceType())
            this.resolved = resolved

            this.prepareRequest!!.disable()
            vm.eventRequestManager()
                .deleteEventRequest(prepareRequest)
            this.prepareRequest = null

            if (((this.refSpec as? PatternReferenceTypeSpec?)?.isPattern == true)) {
                // Class pattern event requests are never considered "resolved", since
                // future class loads might also match.
                // Create and enable a new ClassPrepareRequest to keep trying to resolve.
                this.resolved = null
                this.prepareRequest = refSpec.createPrepareRequest(vm)
                this.prepareRequest!!.enable()
            }
        }
        return resolved
    }

    @Synchronized
    fun remove(vm: VirtualMachine) {
        if (isResolved) {
            vm.eventRequestManager().deleteEventRequest(this.resolved)
        }

        val patternSpec = this.refSpec as? PatternReferenceTypeSpec?
        if (patternSpec?.isPattern == true) {
            // This is a pattern.  Track down and delete
            // all EventRequests matching this spec.
            // Note: Class patterns apply only to ExceptionRequests,
            // so that is all we need to examine.
            val deleteList = vm.eventRequestManager()
                .exceptionRequests()
                .filter { request -> patternSpec.matches(vm, request.exception()) }

            vm.eventRequestManager()
                .deleteEventRequests(deleteList)
        }
    }

    @Throws(Exception::class)
    private fun resolveAgainstPreparedClasses(vm: VirtualMachine): EventRequest {
        for (refType in vm.allClasses()) {
            if (refType.isPrepared && refSpec.matches(vm, refType)) {
                resolved = resolveEventRequest(vm, refType)
            }
        }
        return resolved!!
    }

    @Synchronized
    @Throws(Exception::class)
    fun resolveEagerly(vm: VirtualMachine): EventRequest? {
        if (resolved == null) {
            // Not resolved.  Schedule a prepare request so we
            // can resolve later.
            prepareRequest = refSpec.createPrepareRequest(vm)
            prepareRequest!!.enable()

            // Try to resolve in case the class is already loaded.
            // TODO: The vm.allClasses() method takes some time
            //     As a result, the VM is suspend for noticeable delays
            //     We need to see if we can defer this, or maybe remove this?
            // resolveAgainstPreparedClasses(vm)

            if (resolved != null) {
                prepareRequest!!.disable()
                vm.eventRequestManager()
                    .deleteEventRequest(prepareRequest)
                prepareRequest = null
            }
        }

        if (refSpec is PatternReferenceTypeSpec) {
            if (!refSpec.isUnique) {
                // Class pattern event requests are never
                // considered "resolved", since future class loads
                // might also match.  Create a new
                // ClassPrepareRequest if necessary and keep
                // trying to resolve.
                resolved = null
                if (prepareRequest == null) {
                    prepareRequest = refSpec.createPrepareRequest(vm)
                    prepareRequest!!.enable()
                }
            }
        }
        return resolved
    }
}