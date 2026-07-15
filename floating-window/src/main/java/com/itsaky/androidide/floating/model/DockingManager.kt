

package com.itsaky.androidide.floating.model

import com.itsaky.androidide.floating.window.FloatingWindowState
import com.itsaky.androidide.floating.window.WindowBounds
import com.itsaky.androidide.floating.window.WindowMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The single source of truth for which tabs are currently floating and the state of their windows.
 *
 * Both sides of the same process observe [windows]: the floating window service reconciles overlay
 * windows from it, while the editor activity reconciles its docked tab strip (rendering a
 * placeholder for any tab that is currently floating). All transitions are plain state edits, so
 * neither side needs a static reference to the other.
 */
object DockingManager {
	private val _windows = MutableStateFlow<List<FloatingTab>>(emptyList())
	val windows: StateFlow<List<FloatingTab>> = _windows.asStateFlow()

	private val _events = MutableSharedFlow<DockingEvent>(extraBufferCapacity = 16)
	val events: SharedFlow<DockingEvent> = _events.asSharedFlow()

	fun isFloating(id: String): Boolean = _windows.value.any { it.id == id }

	fun find(id: String): FloatingTab? = _windows.value.firstOrNull { it.id == id }

	/** Float [content] in a new window. No-op if a window with the same id already exists. */
	fun undock(
		content: DockableContent,
		bounds: WindowBounds,
	) {
		_windows.update { current ->
			if (current.any { it.id == content.id }) {
				return@update current
			}
			current + FloatingTab(content, FloatingWindowState(bounds = bounds))
		}
	}

	/** Remove the floating window for [id] and signal that the tab should return to the dock. */
	fun dock(id: String): DockableContent? {
		val tab = find(id) ?: return null
		_windows.update { current -> current.filterNot { it.id == id } }
		_events.tryEmit(DockingEvent.Redock(tab.content))
		return tab.content
	}

	/** Remove the floating window for [id] and signal that the tab should be closed entirely. */
	fun close(id: String): DockableContent? {
		val tab = find(id) ?: return null
		_windows.update { current -> current.filterNot { it.id == id } }
		_events.tryEmit(DockingEvent.Close(tab.content))
		return tab.content
	}

	fun updateBounds(
		id: String,
		bounds: WindowBounds,
	) {
		mutate(id) { it.copy(bounds = bounds) }
	}

	fun setMode(
		id: String,
		mode: WindowMode,
	) {
		mutate(id) { state ->
			when (mode) {
				WindowMode.MAXIMIZED, WindowMode.MINIMIZED ->
					if (state.mode == WindowMode.NORMAL) {
						state.copy(mode = mode, restoreBounds = state.bounds)
					} else {
						state.copy(mode = mode)
					}

				WindowMode.NORMAL ->
					state.copy(mode = mode, bounds = state.restoreBounds)
			}
		}
	}

	private fun mutate(
		id: String,
		transform: (FloatingWindowState) -> FloatingWindowState,
	) {
		_windows.update { current ->
			current.map { tab ->
				if (tab.id == id) tab.copy(state = transform(tab.state)) else tab
			}
		}
	}
}
