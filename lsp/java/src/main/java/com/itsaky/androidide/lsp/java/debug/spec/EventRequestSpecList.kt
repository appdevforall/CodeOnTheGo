package com.itsaky.androidide.lsp.java.debug.spec

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.ClassPrepareEvent
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
        var failure = false
        synchronized(requestSpecs) {
            for (spec in requestSpecs) {
                if (spec.isResolved) continue

                try {
                    val request = spec.resolve(vm, prepareEvent)
                    if (request != null) {
                        logger.info("Set deferrred: {}", spec)
                    }
                } catch (err: Exception) {
                    // TODO: Add something get specific error messages for these exceptions
                    logger.error("Unable to set deferred: {}", spec, err)
                    failure = true
                }
            }
        }

        return !failure
    }

    fun resolveAll() {
        for (requestSpec in requestSpecs) {
            try {
                val request = requestSpec.resolveEagerly(vm)
                if (request != null) {
                    logger.info("Set: {}", requestSpec)
                }
            } catch (err: Exception) {
                // ignored
            }
        }
    }

    fun addEagerlyResolve(spec: EventRequestSpec): Boolean = try {
        requestSpecs.add(spec)
        val request = spec.resolveEagerly(vm)
        if (request != null) {
            logger.info("Set: {}", spec)
        }
        true
    } catch (err: Exception) {
        logger.warn("Unable to set: {}", spec, err)
        false
    }

    fun createBreakpoint(
        source: Source,
        lineNumber: Int,
        threadFilter: ThreadReference? = null
    ): BreakpointSpec {
        val refType = SourceReferenceTypeSpec(source)
        return BreakpointSpec(refType, lineNumber, threadFilter)
    }
}