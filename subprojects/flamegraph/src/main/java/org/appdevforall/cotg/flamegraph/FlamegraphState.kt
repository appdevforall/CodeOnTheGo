package org.appdevforall.cotg.flamegraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Hoistable state for a [Flamegraph]: which subtree is focused (zoomed-into) and which frame is
 * selected/highlighted. Identity is stored as **path keys** (stable `/`-joined child-index paths),
 * never node references, so the state survives recomposition and configuration changes.
 */
@Stable
class FlamegraphState(
	focusedKey: String? = null,
	selectedKey: String? = null,
) {
	/** Path key of the focused subtree, or null for the whole tree. */
	var focusedKey: String? by mutableStateOf(focusedKey)
		private set

	/** Path key of the highlighted frame, or null. */
	var selectedKey: String? by mutableStateOf(selectedKey)
		private set

	/** Zoom into the subtree identified by [key] (null = the whole tree). */
	fun focus(key: String?) {
		focusedKey = key?.ifEmpty { null }
	}

	fun select(key: String?) {
		selectedKey = key
	}

	/** Clear focus and selection. */
	fun reset() {
		focusedKey = null
		selectedKey = null
	}

	companion object {
		val Saver =
			listSaver<FlamegraphState, String?>(
				save = { listOf(it.focusedKey, it.selectedKey) },
				restore = { FlamegraphState(it.getOrNull(0), it.getOrNull(1)) },
			)
	}
}

@Composable
fun rememberFlamegraphState(): FlamegraphState = rememberSaveable(saver = FlamegraphState.Saver) { FlamegraphState() }
