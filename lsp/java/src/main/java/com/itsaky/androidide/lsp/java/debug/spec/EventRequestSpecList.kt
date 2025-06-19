package com.itsaky.androidide.lsp.java.debug.spec

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.request.BreakpointRequest
import org.slf4j.LoggerFactory
import java.util.Collections

/**
 * Manages all the requests to/from the virtual machine.
 *
 * @author Akash Yadav
 */
internal class EventRequestSpecList(
    private val vm: VirtualMachine,
) {
    private val requestSpecs = Collections.synchronizedList(mutableListOf<EventRequestSpec>())

    companion object {
        private val logger = LoggerFactory.getLogger(EventRequestSpecList::class.java)
    }

    /**
     * Resolve all deferred event requests waiting for 'refType'.
     *
     * @return `true` if all requests were resolved, `false` otherwise.
     */
    fun resolve(prepareEvent: ClassPrepareEvent): Boolean {
        logger.debug("resolve prepare event (class={}): {}", prepareEvent.referenceType(), prepareEvent)
        var failure = false
        synchronized(requestSpecs) {
            for (spec in requestSpecs) {
                if (spec.isResolved) {
                    continue
                }

                try {
                    val request = spec.resolve(vm, prepareEvent)
                    if (request != null) {
                        logger.info("resolve: set (deferred): {}", spec)
                    }
                } catch (err: Exception) {
                    // TODO: Add something get specific error messages for these exceptions
                    logger.error("Unable to set deferred: {}", spec, err)
                    failure = true
                }
            }
        }

        logger.debug("resolve(prepareEvent): failure={}", failure)
        return !failure
    }

    fun resolveAll() {
        for (requestSpec in requestSpecs) {
            try {
                val request = requestSpec.resolveEagerly(vm)
                if (request != null) {
                    logger.info("resolveAll: set: {}", requestSpec)
                }
            } catch (err: Exception) {
                // ignored
            }
        }
    }

    fun addEagerlyResolve(
        spec: EventRequestSpec,
        rethrow: Boolean = false,
    ): Boolean = try {
        requestSpecs.add(spec)
        val request = spec.resolveEagerly(vm)
        if (request != null) {
            logger.info("resolveEager: set: {}", spec)
        }
        true
    } catch (err: Exception) {
        logger.warn("Unable to set: {}", spec, err)
        if (rethrow) throw err
        false
    }

    /**
     * Delete a request spec from the spec list.
     *
     * @param spec The request spec to delete.
     * @return `true` if the spec was deleted, `false` otherwise.
     */
    fun delete(spec: EventRequestSpec): Boolean {
        synchronized(requestSpecs) {
            val inx = requestSpecs.indexOf(spec)
            if (inx != -1) {
                val toRemove = requestSpecs[inx]
                toRemove.remove(vm)
                requestSpecs.removeAt(inx)
                return true
            } else {
                return false
            }
        }
    }

    /**
     * Get a list of all the event request specs.
     */
    fun eventRequestSpecs(): List<EventRequestSpec> {
        // We need to make a copy to avoid synchronization problems
        synchronized(requestSpecs) {
            return ArrayList(requestSpecs)
        }
    }

    fun createBreakpoint(
        source: Source,
        lineNumber: Int,
        qualifiedName: String? = null,
        suspendPolicy: Int = BreakpointRequest.SUSPEND_NONE,
        threadFilter: ThreadReference? = null,
    ): BreakpointSpec {
        val refType = SourceReferenceTypeSpec(source, qualifiedName)
        return BreakpointSpec(refType, suspendPolicy, lineNumber, threadFilter)
    }

    fun createBreakpoint(
        source: Source,
        methodId: String,
        methodArgs: List<String> = emptyList(),
        qualifiedName: String? = null,
        suspendPolicy: Int = BreakpointRequest.SUSPEND_NONE,
        threadFilter: ThreadReference? = null
    ): BreakpointSpec {
        val refType = SourceReferenceTypeSpec(source, qualifiedName)
        return BreakpointSpec(
            refType,
            suspendPolicy,
            methodId,
            methodArgs,
            threadFilter
        )
    }
}