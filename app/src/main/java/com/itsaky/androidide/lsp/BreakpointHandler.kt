package com.itsaky.androidide.lsp

import com.google.common.collect.HashBasedTable
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointDefinition
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.properties.Delegates

private interface BreakpointEvent {
    data class DocChange(
        val event: DocumentChangeEvent
    ): BreakpointEvent

    data class Toggle(
        val file: File,
        val line: Int
    ): BreakpointEvent

}

class BreakpointHandler {

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val scope = CoroutineScope(newSingleThreadContext("BreakpointHandler"))
    private val events = Channel<BreakpointEvent>(capacity = Channel.UNLIMITED)
    private val breakpoints = HashBasedTable.create<String, Int, PositionalBreakpoint>()
    private var onSetBreakpoints: (List<BreakpointDefinition>) -> Unit by Delegates.notNull()
    private val listeners = CopyOnWriteArrayList<EventListener>()

    val allBreakpoints: List<BreakpointDefinition>
        get() = ArrayList(breakpoints.rowMap().flatMap { (_, bp) -> bp.values })

    companion object {
        private val logger = LoggerFactory.getLogger(BreakpointHandler::class.java)
    }

    fun breakpointsInFile(path: String) = ArrayList(breakpoints.row(path).values)

    fun begin(consumer: (List<BreakpointDefinition>) -> Unit) {
        onSetBreakpoints = consumer

        scope.launch {
            for (event in events) {
                process(event)
            }
        }
    }

    fun listen(listener: EventListener) {
        if (listeners.contains(listener)) {
            logger.warn("listener {} is already added", listener)
            return
        }

        listeners.add(listener)
    }

    suspend fun change(event: DocumentChangeEvent) {
        events.send(BreakpointEvent.DocChange(event))
    }

    suspend fun toggle(file: File, line: Int) {
        events.send(BreakpointEvent.Toggle(file, line))
    }

    private fun process(event: BreakpointEvent) {
        when (event) {
            is BreakpointEvent.DocChange -> onChange(event)
            is BreakpointEvent.Toggle -> onToggle(event)
        }
    }

    private fun onChange(event: BreakpointEvent.DocChange) {
        val ev = event.event
        val path = ev.file.toRealPath().toString()
        logger.debug("change: range={}-{}", ev.changeRange.start.line, ev.changeRange.end.line)
        if (ev.changeType == ChangeType.NEW_TEXT) {
            logger.debug("new content set to file {}. removing all breakpoints", path)

            // all of the editor's text was invalidated
            // remove all breakpoints in this file
            val breakpoints = breakpoints.row(path)
            synchronized(breakpoints) {
                // remove breakpoints from client if we're connected
                // create a copy, because 'breakpoints' may be cleared even before the
                // coroutine is launched
                val localBreakpoints = ArrayList(breakpoints.values)
                localBreakpoints.forEach { notifyRemoved(path, it.line) }

                onSetBreakpoints(localBreakpoints)
                breakpoints.clear()
            }

            return
        }

        val range = ev.changeRange
        val (start, end) = range

        val fileBreakpoints = breakpoints.row(path)
        val newBreakpoints = synchronized(breakpoints) {
            val newBreakpoints = mutableMapOf<Int, PositionalBreakpoint>()
            for ((line, breakpoint) in fileBreakpoints) {
                if (line < start.line) {
                    // this breakpoint lies before the edit region, or on the same line
                    // as a result, it doesn't require an update to the line number
                    logger.debug("keep breakpoint at line {} in file {}", line, path)
                    newBreakpoints[line] = breakpoint
                    continue
                }

                if (start.line != end.line && range.containsLine(line)) {
                    // in case of multi-line edits, if the breakpoint line was in the edited region
                    // then the breakpoint is no longer valid
                    // in such cases, remove all breakpoints which were in the edited region
                    logger.debug("removing breakpoint at line {} in file {} because the breakpoint line was removed by editing file", line, path)
                    notifyRemoved(path, line)
                    continue
                }

                // we assume that the end line always > start line
                var lineDelta = end.line - start.line
                if (ev.changeType == ChangeType.DELETE) {
                    // content was deleted, so the lines must be reduced by delta
                    lineDelta = -lineDelta
                }

                logger.debug("updating breakpoints in file {} by delta {}", path, lineDelta)

                // for breakpoints that lie beyond the edit range, we need to update their line
                // numbers according to the delta in change range
                val newLine = line + lineDelta
                logger.debug("breakpoint at line {} moved to line {} in file {}", line, newLine, path)
                newBreakpoints[newLine] = breakpoint.copy(line = newLine)
                notifyMoved(path, line, newLine)
            }

            breakpoints.row(path).apply {
                clear()
                putAll(newBreakpoints)
            }

            newBreakpoints.values.toList()
        }

        // publish the new breakpoints to the client, if connected
        onSetBreakpoints(newBreakpoints)
    }

    private fun onToggle(event: BreakpointEvent.Toggle) {
        val (file, line) = event
        val breakpoint = PositionalBreakpoint(
            source = Source(
                path = file.absolutePath,
                name = file.name
            ),
            line = line
        )

        val path = file.canonicalPath

        val remove = breakpoints.contains(path, line)
        logger.debug("{} breakpoint at line {} in {}", if (remove) "remove" else "add", line, path)

        if (remove) {
            breakpoints.remove(path, line)
            notifyRemoved(path, line)
        } else {
            breakpoints.put(path, line, breakpoint)
            notifyAdded(path, line)
        }

        notifyToggled(path, line)

        // if we're already connected to a client, update the client as well
        val fileBreakpoints = breakpoints.row(path)
        onSetBreakpoints(ArrayList(fileBreakpoints.values))
    }

    private fun notifyAdded(file: String, line: Int) {
        for (listener in listeners) {
            listener.onAddBreakpoint(file, line)
        }
    }

    private fun notifyRemoved(file: String, line: Int) {
        for (listener in listeners) {
            listener.onRemoveBreakpoint(file, line)
        }
    }

    private fun notifyToggled(file: String, line: Int) {
        for (listener in listeners) {
            listener.onToggle(file, line)
        }
    }

    private fun notifyMoved(file: String, oldLine: Int, newLine: Int) {
        for (listener in listeners) {
            listener.onMoveBreakpoint(file, oldLine, newLine)
        }
    }

    abstract class EventListener {
        open fun onAddBreakpoint(file: String, line: Int) {}
        open fun onRemoveBreakpoint(file: String, line: Int) {}
        open fun onToggle(file: String, line: Int) {}
        open fun onMoveBreakpoint(file: String, oldLine: Int, newLine: Int) {}
    }
}