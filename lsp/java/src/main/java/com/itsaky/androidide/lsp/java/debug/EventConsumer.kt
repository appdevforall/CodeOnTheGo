package com.itsaky.androidide.lsp.java.debug

import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.ClassUnloadEvent
import com.sun.jdi.event.Event
import com.sun.jdi.event.ExceptionEvent
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.event.StepEvent
import com.sun.jdi.event.ThreadDeathEvent
import com.sun.jdi.event.ThreadStartEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.event.VMStartEvent
import com.sun.jdi.event.WatchpointEvent


/**
 * A consumer of JDWP events.
 */
interface EventConsumer {
    fun vmStartEvent(e: VMStartEvent) {}
    fun vmDeathEvent(e: VMDeathEvent) {}
    fun vmDisconnectEvent(e: VMDisconnectEvent) {}

    fun threadStartEvent(e: ThreadStartEvent) {}
    fun threadDeathEvent(e: ThreadDeathEvent) {}

    fun classPrepareEvent(e: ClassPrepareEvent) {}
    fun classUnloadEvent(e: ClassUnloadEvent) {}

    fun breakpointEvent(e: BreakpointEvent) {}
    fun fieldWatchEvent(e: WatchpointEvent) {}
    fun stepEvent(e: StepEvent) {}
    fun exceptionEvent(e: ExceptionEvent) {}

    fun methodEntryEvent(e: MethodEntryEvent) {}
    fun methodExitEvent(e: MethodExitEvent): Boolean = false
    fun vmInterrupted() {}

    fun receivedEvent(event: Event) {}
}