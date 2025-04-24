package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.java.debug.spec.EventRequestSpecList
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.ClassUnloadEvent
import com.sun.jdi.event.Event
import com.sun.jdi.event.ExceptionEvent
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.event.StepEvent
import com.sun.jdi.event.ThreadDeathEvent
import com.sun.jdi.event.ThreadStartEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.event.VMStartEvent
import com.sun.jdi.event.WatchpointEvent
import com.sun.jdi.request.EventRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory


/**
 * Handles events from the VM.
 *
 * @author Akash Yadav
 */
internal class EventHandler(
    private val vm: VirtualMachine,
    private val stopOnVmStart: Boolean,
    private val consumer: EventConsumer
) : AutoCloseable {

    internal val eventRequestSpecList = EventRequestSpecList(vm)

    @Volatile
    private var connected = true
    private var vmDied = false
    private var completed = false

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val adapterContext = newSingleThreadContext("JDWPEventHandler")
    private val adapterScope = CoroutineScope(adapterContext)
    private var eventsJob: Job? = null

    companion object {
        private val logger = LoggerFactory.getLogger(EventHandler::class.java)
    }

    /**
     * Run the event handler.
     */
    fun startListening() {
        eventsJob = adapterScope.launch {
            val job = coroutineContext.job
            val queue = vm.eventQueue()
            while (connected && job.isActive && !job.isCancelled) {
                try {
                    val events = queue.remove()
                    var resumeVm = false
                    for (event in events.eventIterator()) {
                        logger.info("startListening: received event: {}", event)
                        val stopForEvent = handleEvent(event)
                        logger.info(
                            "startListening: handled event: {}, resumeVm={}, stopForEvent={}",
                            event,
                            resumeVm,
                            stopForEvent
                        )

                        resumeVm = resumeVm || !stopForEvent
                    }

                    if (resumeVm) {
                        events.resume()
                    } else if (events.suspendPolicy() == EventRequest.SUSPEND_ALL) {
                        // notify consumer that the VM has interrupted
                        // TODO: Get information about the current thread
                        logger.info("startListening: VM interrupted")
                        consumer.vmInterrupted()
                    }
                } catch (interupption: InterruptedException) {
                    // Ignore, changes will be seen at top of the loop
                } catch (err: VMDisconnectedException) {
                    handleDisconnectedException()
                }
            }

            completed = true
            eventsJob = null
            logger.info("EventHandler completed")
        }
    }

    /**
     * Handle the event.
     *
     * @param event The event to handle.
     * @return `true` if the VM should be stopped, false otherwise.
     */
    private fun handleEvent(event: Event): Boolean {
        consumer.receivedEvent(event)

        return when (event) {
            is ExceptionEvent -> exceptionEvent(event)
            is BreakpointEvent -> breakpointEvent(event)
            is WatchpointEvent -> fieldWatchEvent(event)
            is StepEvent -> stepEvent(event)
            is MethodEntryEvent -> methodEntryEvent(event)
            is MethodExitEvent -> methodExitEvent(event)
            is ClassPrepareEvent -> classPrepareEvent(event)
            is ClassUnloadEvent -> classUnloadEvent(event)
            is ThreadStartEvent -> threadStartEvent(event)
            is ThreadDeathEvent -> threadDeathEvent(event)
            is VMStartEvent -> vmStartEvent(event)
            else -> handleExitEvent(event)
        }
    }

    /**
     * @see [handleEvent]
     */
    private fun vmStartEvent(event: VMStartEvent): Boolean {
        consumer.vmStartEvent(event)
        return stopOnVmStart
    }

    /**
     * @see [handleEvent]
     */
    private fun breakpointEvent(event: BreakpointEvent): Boolean {
        consumer.breakpointEvent(event)
        return true
    }

    /**
     * @see [handleEvent]
     */
    private fun methodEntryEvent(event: MethodEntryEvent): Boolean {
        consumer.methodEntryEvent(event)
        return true
    }

    /**
     * @see [handleEvent]
     */
    private fun methodExitEvent(event: MethodExitEvent): Boolean {
        return consumer.methodExitEvent(event)
    }

    /**
     * @see [handleEvent]
     */
    private fun fieldWatchEvent(event: WatchpointEvent): Boolean {
        consumer.fieldWatchEvent(event)
        return true
    }

    /**
     * @see [handleEvent]
     */
    private fun stepEvent(event: StepEvent): Boolean {
        consumer.stepEvent(event)
        return true
    }

    /**
     * @see [handleEvent]
     */
    private fun classPrepareEvent(event: ClassPrepareEvent): Boolean {
        consumer.classPrepareEvent(event)

        if (!eventRequestSpecList.resolve(event)) {
            logger.info("Stopping VM due to deferred breakpoint errors.")
            return true
        } else {
            return false
        }
    }


    /**
     * @see [handleEvent]
     */
    private fun classUnloadEvent(event: ClassUnloadEvent): Boolean {
        consumer.classUnloadEvent(event)
        return false
    }

    /**
     * @see [handleEvent]
     */
    private fun exceptionEvent(event: ExceptionEvent): Boolean {
        consumer.exceptionEvent(event)
        return true
    }

    /**
     * @see [handleEvent]
     */
    private fun threadStartEvent(event: ThreadStartEvent): Boolean {
        // TODO: Add thread tracker
        consumer.threadStartEvent(event)
        return false
    }

    /**
     * @see [handleEvent]
     */
    private fun threadDeathEvent(event: ThreadDeathEvent): Boolean {
        // TODO: Remove thread tracker
        consumer.threadDeathEvent(event)
        return false
    }

    private fun eventThread(event: Event) = when (event) {
        is ClassPrepareEvent -> event.thread()
        is LocatableEvent -> event.thread()
        is ThreadStartEvent -> event.thread()
        is ThreadDeathEvent -> event.thread()
        is VMStartEvent -> event.thread()
        else -> null
    }

    @Synchronized
    private fun handleDisconnectedException() {
        // Flush the event queue. Dealing only with vm death or disconnection to ensure
        // proper termination of this handler

        val queue = vm.eventQueue()
        while (connected) {
            try {
                val eventSet = queue.remove()
                val iter = eventSet.eventIterator()
                while (iter.hasNext()) {
                    handleExitEvent(iter.next())
                }
            } catch (exc: InterruptedException) {
                // ignore
            } catch (exc: InternalError) {
                // ignore
            }
        }
    }

    private fun handleExitEvent(event: Event): Boolean {
        when (event) {
            is VMDeathEvent -> {
                vmDied = true
                return vmDeathEvent(event)
            }

            is VMDisconnectEvent -> {
                connected = false
                if (!vmDied) {
                    vmDisconnectEvent(event)
                }

                return false
            }

            else -> throw IllegalArgumentException("Unknown event type: $event")
        }
    }

    private fun vmDeathEvent(event: VMDeathEvent): Boolean {
        consumer.vmDeathEvent(event)
        return false
    }

    private fun vmDisconnectEvent(event: VMDisconnectEvent): Boolean {
        consumer.vmDisconnectEvent(event)
        return false
    }

    override fun close() {
        connected = false
        eventsJob?.cancel(CancellationException("EventHandler closed"))
        eventsJob = null
    }
}