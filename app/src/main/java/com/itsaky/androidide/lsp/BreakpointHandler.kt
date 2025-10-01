package com.itsaky.androidide.lsp

import com.google.common.collect.HashBasedTable
import com.google.common.collect.ImmutableTable
import com.google.common.collect.Table
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointDefinition
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.MethodBreakpoint
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.repositories.BreakpointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.Delegates

private interface BreakpointEvent {
    data class DocChange(
        val event: DocumentChangeEvent
    ): BreakpointEvent

    data class Toggle(
        val file: File,
        val line: Int
    ): BreakpointEvent

    object Save : BreakpointEvent
    object Clear : BreakpointEvent
}

private data class BpState(
    val positional: Table<String, Int, PositionalBreakpoint>,
    val method: Table<String, String, MethodBreakpoint>
)

class BreakpointHandler {

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val scope = CoroutineScope(newSingleThreadContext("BreakpointHandler"))
    private val events = Channel<BreakpointEvent>(capacity = Channel.UNLIMITED)
    private val _highlightedLocation = MutableStateFlow<Pair<String, Int>?>(null)
    private var onSetBreakpoints: (List<BreakpointDefinition>) -> Unit by Delegates.notNull()
    private val listeners = CopyOnWriteArrayList<EventListener>()

    private val stateRef = AtomicReference(
        BpState(ImmutableTable.of(), ImmutableTable.of())
    )

    val highlightedLocationState: StateFlow<Pair<String, Int>?>
        get() = _highlightedLocation.asStateFlow()

    val highlightedLocation: Pair<String, Int>?
        get() = highlightedLocationState.value

    val allBreakpoints: List<BreakpointDefinition>
        get() = stateRef.get().let { s ->
            buildList {
                addAll(s.positional.values())
                addAll(s.method.values())
            }
        }

    companion object {
        private val logger = LoggerFactory.getLogger(BreakpointHandler::class.java)
    }

    fun highlightLocation(file: String, line: Int) {
        this._highlightedLocation.update { file to line }
        notifyHighlighted(file, line)
    }

    fun unhighlightHighlightedLocation() {
        this._highlightedLocation.update { null }
        notifyUnhighlight()
    }

    fun getProjectLocation(): String {
        val projectDir = IProjectManager.getInstance().projectDir
        return projectDir.absolutePath
    }

    fun begin(consumer: (List<BreakpointDefinition>) -> Unit) {
        scope.launch {
            val loadedBreakpoints = BreakpointRepository.getBreakpointsLocalStored(getProjectLocation())

            refreshBreakpoints(loadedBreakpoints)
            onSetBreakpoints = consumer
            onSetBreakpoints(loadedBreakpoints)

            for (event in events) {
                process(event)
            }
        }
    }

    fun addListener(listener: EventListener) {
        if (listeners.contains(listener)) {
            logger.warn("listener {} is already added", listener)
            return
        }

        listeners.add(listener)
    }

    fun removeListener(listener: EventListener) {
        listeners.remove(listener)
    }

    suspend fun storedPositionalBreakpoints(): List<PositionalBreakpoint> = BreakpointRepository.getPositionalBreakpoints(getProjectLocation())

    suspend fun change(event: DocumentChangeEvent) {
        events.send(BreakpointEvent.DocChange(event))
    }

    suspend fun toggle(file: File, line: Int) {
        events.send(BreakpointEvent.Toggle(file, line))
    }

    private fun refreshBreakpoints(loadedBreakpoints: List<BreakpointDefinition>) {
        val newPos = HashBasedTable.create<String, Int, PositionalBreakpoint>()
        val newMethod = HashBasedTable.create<String, String, MethodBreakpoint>()

        for (bp in loadedBreakpoints) {
            val path = bp.source.path
            when (bp) {
                is PositionalBreakpoint -> {
                    newPos.put(path, bp.line, bp)
                }
                is MethodBreakpoint -> {
                    newMethod.put(path, bp.methodId, bp)
                }
            }
        }

        val snap = BpState(
            positional = ImmutableTable.copyOf(newPos),
            method = ImmutableTable.copyOf(newMethod)
        )

        stateRef.set(snap)
    }

    private fun onSave() {
        scope.launch(Dispatchers.IO) {
            val snap = stateRef.get()
            val breakpointsToSave = buildList {
                addAll(snap.positional.values())
                addAll(snap.method.values())
            }
            BreakpointRepository.saveBreakpoints(getProjectLocation(), breakpointsToSave)
            logger.debug("Breakpoints saved to disk.")
        }
    }

    private fun onClear() {
        scope.launch {
            stateRef.set(BpState(
                positional = ImmutableTable.of(),
                method     = ImmutableTable.of()
            ))

            onSetBreakpoints(emptyList())
            BreakpointRepository.clearBreakpoints(getProjectLocation())

            logger.debug("All breakpoints cleared (memory + disk).")
        }
    }

    private fun process(event: BreakpointEvent) {
        when (event) {
            is BreakpointEvent.DocChange -> onChange(event)
            is BreakpointEvent.Toggle -> onToggle(event)
            is BreakpointEvent.Save -> onSave()
            is BreakpointEvent.Clear -> onClear()
        }
    }

    private fun onChange(event: BreakpointEvent.DocChange) {
        val ev = event.event
        val path = ev.file.toRealPath().toString()
        logger.debug("change: range={}-{}", ev.changeRange.start.line, ev.changeRange.end.line)

        val current = stateRef.get()

        val newPos = HashBasedTable.create(current.positional)

        if (ev.changeType == ChangeType.NEW_TEXT) {
            logger.debug("new content set to file {}. removing all breakpoints", path)

            // all of the editor's text was invalidated
            // remove all breakpoints in this file
            val localBreakpoints = ArrayList(newPos.row(path).values)
            localBreakpoints.forEach { notifyRemoved(path, it.line) }

            onSetBreakpoints(localBreakpoints)
            newPos.row(path).clear()

            stateRef.set(current.copy(positional = ImmutableTable.copyOf(newPos)))
            events.trySend(BreakpointEvent.Clear)
            return
        }

        val range = ev.changeRange
        val (start, end) = range

        val fileBreakpoints = HashMap(newPos.row(path))
        val updated = mutableMapOf<Int, PositionalBreakpoint>()

        for ((line, bp) in fileBreakpoints) {
            if (line < start.line) {
                updated[line] = bp
                continue
            }

            if (start.line != end.line && range.containsLine(line)) {
                logger.debug("removing breakpoint at line {} in file {}", line, path)
                notifyRemoved(path, line)
                continue
            }

            var delta = end.line - start.line
            if (ev.changeType == ChangeType.DELETE) delta = -delta

            val newLine = line + delta
            updated[newLine] = bp.copy(line = newLine)
            notifyMoved(path, line, newLine)
        }

        newPos.row(path).apply {
            clear()
            putAll(updated)
        }

        val newList = updated.values.toList()
        onSetBreakpoints(newList)

        stateRef.set(current.copy(positional = ImmutableTable.copyOf(newPos)))
    }

    private fun onToggle(event: BreakpointEvent.Toggle) {
        val (file, line) = event
        val path = file.canonicalPath

        val breakpoint = PositionalBreakpoint(
            source = Source(path = file.absolutePath, name = file.name),
            line = line
        )

        val current = stateRef.get()
        val newPos = HashBasedTable.create(current.positional)

        val remove = newPos.contains(path, line)
        logger.debug("{} breakpoint at line {} in {}", if (remove) "remove" else "add", line, path)

        if (remove) {
            newPos.remove(path, line)
            notifyRemoved(path, line)
        } else {
            newPos.put(path, line, breakpoint)
            notifyAdded(path, line)
        }

        notifyToggled(path, line)

        val fileBreakpoints = newPos.row(path).values
        onSetBreakpoints(ArrayList(fileBreakpoints))

        stateRef.set(current.copy(positional = ImmutableTable.copyOf(newPos)))
        events.trySend(BreakpointEvent.Save)
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

    private fun notifyHighlighted(file: String, line: Int) {
        for (listener in listeners) {
            listener.onHighlightLine(file, line)
        }
    }

    private fun notifyUnhighlight() {
        for (listener in listeners) {
            listener.onUnhighlight()
        }
    }

    interface EventListener {
        fun onAddBreakpoint(file: String, line: Int) {}
        fun onRemoveBreakpoint(file: String, line: Int) {}
        fun onToggle(file: String, line: Int) {}
        fun onMoveBreakpoint(file: String, oldLine: Int, newLine: Int) {}
        fun onHighlightLine(file: String, line: Int) {}
        fun onUnhighlight()
    }
}