package com.itsaky.androidide.lsp

import androidx.annotation.VisibleForTesting
import com.google.common.collect.HashBasedTable
import com.google.common.collect.ImmutableTable
import com.google.common.collect.Table
import com.google.common.collect.TreeBasedTable
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointDefinition
import com.itsaky.androidide.lsp.debug.model.MethodBreakpoint
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.repositories.BreakpointRepository
import com.itsaky.androidide.repositories.StoredBreakpointsType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private interface BreakpointEvent {
	data class DocChange(
		val event: DocumentChangeEvent
	) : BreakpointEvent

	data class Toggle(
		val file: File,
		val line: Int
	) : BreakpointEvent

	object Save : BreakpointEvent
}

private data class BpState(
	val positional: Table<String, Int, PositionalBreakpoint>,
	val method: Table<String, String, MethodBreakpoint>
) {
	companion object {
		val EMPTY = BpState(ImmutableTable.of(), ImmutableTable.of())
	}
}

class BreakpointHandler {

	@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
	private val scope = CoroutineScope(newSingleThreadContext("BreakpointHandler"))
	private val events = Channel<BreakpointEvent>(capacity = Channel.UNLIMITED)
	private val _highlightedLocation = MutableStateFlow<Pair<String, Int>?>(null)
	private var onSetBreakpoints: ((List<BreakpointDefinition>) -> Unit)? = null
	private val listeners = CopyOnWriteArrayList<EventListener>()

	private val stateRef = AtomicReference(BpState.EMPTY)
	private var saveJob: Job? = null

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

		@VisibleForTesting
		internal fun computeNewBreakpointPosition(
			line: Int,
			column: Int,
			start: Position,
			end: Position,
			changeType: ChangeType,
		): Pair<Int, Int> {
			var newLine = line
			var newColumn = column

			if (changeType == ChangeType.INSERT) {

				// insertion before breakpoint line
				if (line > start.line) {
					// shift down
					newLine += end.line - start.line
				}

				// insertion on breakpoint line, after start column
				else if (line == start.line && column > start.column) {
					if (start.line == end.line) {
						// same line insertion, shift column right
						newColumn += end.column - start.column
					} else {
						// multi-line insertion
						// adjust line and column
						newLine += end.line - start.line
						newColumn = column - start.column + end.column
					}
				}
			} else if (changeType == ChangeType.DELETE) {
				// breakpoint after deletion range
				if (line > end.line) {
					// shift up
					newLine -= end.line - start.line
				}

				// breakpoint after last line of deletion, after end column
				else if (line == end.line && column > end.column) {
					if (start.line == end.line) {
						// Single-line deletion
						newColumn -= (end.column - start.column);
					} else {
						// Multi-line deletion
						newLine = start.line
						newColumn = start.column + (column - end.column);
					}
				}

				// breakpoint within deleted range
				else if ((line > start.line || (line == start.line && column >= start.column))
					&& (line < end.line || (line == end.line && column <= end.column))
				) {
					// mark for deletion
					newLine = -1
					newColumn = -1
				}
			}

			return Pair(newLine, newColumn)
		}
	}

	fun highlightLocation(file: String, line: Int) {
		this._highlightedLocation.update { file to line }
		notifyHighlighted(file, line)
	}

	fun unhighlightHighlightedLocation() {
		this._highlightedLocation.update { null }
		notifyUnhighlight()
	}

	fun begin(consumer: (List<BreakpointDefinition>) -> Unit) {
		onSetBreakpoints = consumer

		scope.launch {
			val projectDir = IProjectManager.getInstance().projectDir
			val loadedBreakpoints = BreakpointRepository.getStoredBreakpoints(projectDir)

			refreshBreakpoints(loadedBreakpoints)
			notifyBreakpointsUpdated(loadedBreakpoints)

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

	suspend fun positionalBreakpointsInFile(file: File): List<PositionalBreakpoint> =
		BreakpointRepository.getStoredBreakpoints(IProjectManager.getInstance().projectDir)
			.mapNotNull { breakpoint ->
				if (breakpoint.source.path != file.absolutePath || breakpoint !is PositionalBreakpoint) {
					return@mapNotNull null
				}

				breakpoint
			}

	suspend fun change(event: DocumentChangeEvent) {
		events.send(BreakpointEvent.DocChange(event))
	}

	suspend fun toggle(file: File, line: Int) {
		events.send(BreakpointEvent.Toggle(file, line))
	}

	private fun refreshBreakpoints(loadedBreakpoints: StoredBreakpointsType) {
		val newPos = HashBasedTable.create<String, Int, PositionalBreakpoint>()
		val newMethod = HashBasedTable.create<String, String, MethodBreakpoint>()

		for (bp in loadedBreakpoints) {
			val path = bp.source.path
			when (bp) {
				is PositionalBreakpoint -> newPos.put(path, bp.line, bp)
				is MethodBreakpoint -> newMethod.put(path, bp.methodId, bp)
			}
		}

		val snap = BpState(
			positional = ImmutableTable.copyOf(newPos),
			method = ImmutableTable.copyOf(newMethod)
		)

		stateRef.set(snap)
	}

	private fun process(event: BreakpointEvent) = runCatching {
		when (event) {
			is BreakpointEvent.DocChange -> onChange(event)
			is BreakpointEvent.Toggle -> onToggle(event)
			is BreakpointEvent.Save -> onSave()
		}
	}.onFailure { err ->
		logger.error("Failed to handle event {}", event.javaClass.simpleName, err)
	}

	private fun onChange(event: BreakpointEvent.DocChange) {
		val ev = event.event
		val range = ev.changeRange
		val (start, end) = range

		val path = ev.file.toRealPath().toString()
		logger.debug(
			"change({}): range={},{}-{},{}", when (ev.changeType) {
				ChangeType.NEW_TEXT -> "new text"
				ChangeType.DELETE -> "delete"
				ChangeType.INSERT -> "insert"
			}, start.line, start.column, end.line, end.column
		)

		if (end.line - start.line == 0) {
			// single line edits don't alter line numbers
			// hence we don't need to update the breakpoints
			logger.debug("no lines changed")
			return
		}

		val current = stateRef.get()

		val newPos = HashBasedTable.create(current.positional)
		val breakpoints = newPos.row(path)

		if (ev.changeType == ChangeType.NEW_TEXT) {
			logger.debug("new content set to file {}. removing all breakpoints", path)

			// All of the editor's text was invalidated
			// remove all breakpoints in this file
			val localBreakpoints = ArrayList(breakpoints.values)
			localBreakpoints.forEach { notifyRemoved(path, it.line) }

			notifyBreakpointsUpdated(localBreakpoints)
			breakpoints.clear()

			stateRef.set(current.copy(positional = ImmutableTable.copyOf(newPos)))
			events.trySend(BreakpointEvent.Save)
			return
		}

		val fileBreakpoints = TreeMap(breakpoints)
		val updated = mutableMapOf<Int, PositionalBreakpoint>()

		for ((_, bp) in fileBreakpoints) {
			val line = bp.line
			val column = bp.column
			val (newLine, newColumn) = computeNewBreakpointPosition(
				line,
				column,
				start,
				end,
				ev.changeType
			)

			if (newLine == line && newColumn == column) {
				logger.debug("keep breakpoint at line {} in file {}", line, path)
				updated[line] = bp
				continue
			}

			if (newLine == -1 && newColumn == -1) {
				logger.debug("remove breakpoint at {},{} in file {}", line, column, path)
				notifyRemoved(path, line)
				continue
			}

			logger.debug("move breakpoint at line {} to line {} in file {}", line, newLine, path)
			updated[newLine] = bp.copy(line = newLine)
			notifyMoved(path, line, newLine)
		}

		breakpoints.apply {
			clear()
			putAll(updated)
		}

		stateRef.set(current.copy(positional = ImmutableTable.copyOf(newPos)))
		events.trySend(BreakpointEvent.Save)

		notifyBreakpointsUpdated(newBreakpoints = updated.values.toList())
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
		notifyBreakpointsUpdated(ArrayList(fileBreakpoints))

		stateRef.set(current.copy(positional = ImmutableTable.copyOf(newPos)))
		events.trySend(BreakpointEvent.Save)
	}

	private fun onSave() {
		saveJob?.cancel()
		saveJob = scope.launch(Dispatchers.IO) {
			delay(1000)
			val snap = stateRef.get()
			val breakpointsToSave = buildList {
				addAll(snap.positional.values())
				addAll(snap.method.values())
			}
			BreakpointRepository.saveBreakpoints(
				projectDir = IProjectManager.getInstance().projectDir,
				breakpoints = breakpointsToSave
			)
			logger.debug("Breakpoints saved to disk.")
		}
	}

	private fun notifyBreakpointsUpdated(newBreakpoints: List<BreakpointDefinition>) {
		onSetBreakpoints?.invoke(newBreakpoints)
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