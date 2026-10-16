package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.Method
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.EventQueue
import com.sun.jdi.event.EventSet

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
