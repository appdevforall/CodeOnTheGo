package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.Event
import com.sun.jdi.event.EventQueue
import com.sun.jdi.event.EventSet
import com.sun.jdi.request.BreakpointRequest
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VmUtils")

fun VirtualMachine.relativePath(type: ReferenceType): String = type.sourcePathOrNull() ?: ""

/**
 * Check whether the breakpoint request's source is same as [source].
 */
fun VirtualMachine.isBreakpointInSource(
	br: BreakpointRequest,
	source: Source,
): Boolean {
	val relativePath = br.location().sourcePathOrNull()
	if (relativePath == null) {
		logger.warn("No source information for {}", br.location())
		return false
	}
	return matchesSourcePath(source.path, relativePath)
}

/**
 * Check whether the breakpoint request's line number is same as [line].
 */
fun VirtualMachine.isBreakpointLine(
	br: BreakpointRequest,
	line: Int,
): Boolean = line == br.location().lineNumberInSource()

/**
 * Returns an iterator over the events in the event queue.
 */
fun VirtualMachine.events(): Iterator<EventSet> =
	object : AbstractIterator<EventSet>() {
		override fun computeNext() {
			val eventQueue = this@events.eventQueue()
			var eventSet = eventQueue.tryRemove()
			while (eventSet != null) {
				setNext(eventSet)
				eventSet = eventQueue.tryRemove()
			}
			done()
		}
	}

/**
 * Tries to remove the next event set from the event queue, or return `null` if it cannot get the
 * events.
 */
fun EventQueue.tryRemove(): EventSet? =
	try {
		this.remove()
	} catch (err: VMDisconnectedException) {
		null
	} catch (err: InterruptedException) {
		null
	}

val Method.isOpaque: Boolean
	get() = isAbstract || isNative
