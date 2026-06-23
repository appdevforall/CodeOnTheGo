

package com.itsaky.androidide.floating.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.floating.model.DockAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The chrome for a normal (non-minimized) floating editor window: a slim draggable title bar with
 * window controls, a resize grip, and the editor [content] below. The border tints to the theme
 * accent while [focused] (i.e. the window currently holds keyboard focus).
 */
@Composable
fun FloatingWindowChrome(
	title: String,
	focused: Boolean,
	maximized: Boolean,
	onDrag: (Float, Float) -> Unit,
	onDragStopped: () -> Unit,
	onResize: (Float, Float) -> Unit,
	onResizeStopped: () -> Unit,
	onMinimize: () -> Unit,
	onToggleMaximize: () -> Unit,
	onDock: () -> Unit,
	onClose: () -> Unit,
	actions: List<DockAction>,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val scheme = MaterialTheme.colorScheme
	val borderColor = if (focused) scheme.primary else scheme.outline
	Surface(
		modifier = modifier.fillMaxSize(),
		shape = RoundedCornerShape(12.dp),
		color = scheme.surface,
		contentColor = scheme.onSurface,
		border = BorderStroke(1.dp, borderColor),
		tonalElevation = 3.dp,
		shadowElevation = 6.dp,
	) {
		Box(Modifier.fillMaxSize()) {
			Column(Modifier.fillMaxSize()) {
				TitleBar(
					title = title,
					maximized = maximized,
					actions = actions,
					onDrag = onDrag,
					onDragStopped = onDragStopped,
					onMinimize = onMinimize,
					onToggleMaximize = onToggleMaximize,
					onDock = onDock,
					onClose = onClose,
				)
				Box(
					Modifier
						.fillMaxWidth()
						.weight(1f),
				) {
					content()
				}
			}
			ResizeHandle(
				onResize = onResize,
				onResizeStopped = onResizeStopped,
				modifier = Modifier.align(Alignment.BottomEnd),
			)
		}
	}
}

/** Collapsed representation of a floating window: a draggable pill; tapping it restores the window. */
@Composable
fun MinimizedBubble(
	title: String,
	onRestore: () -> Unit,
	onDrag: (Float, Float) -> Unit,
	onDragStopped: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val scheme = MaterialTheme.colorScheme
	val monogram = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
	Surface(
		modifier = modifier.pointerInput(Unit) {
			detectDragGestures(
				onDragEnd = onDragStopped,
				onDragCancel = onDragStopped,
			) { change, dragAmount ->
				change.consume()
				onDrag(dragAmount.x, dragAmount.y)
			}
		},
		shape = RoundedCornerShape(percent = 50),
		color = scheme.primaryContainer,
		contentColor = scheme.onPrimaryContainer,
		border = BorderStroke(1.dp, scheme.outline),
		shadowElevation = 6.dp,
	) {
		Row(
			modifier = Modifier
				.clickable(onClick = onRestore)
				.padding(horizontal = 12.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(text = monogram, style = MaterialTheme.typography.titleMedium)
			Spacer(Modifier.width(8.dp))
			Text(
				text = title,
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 120.dp),
			)
		}
	}
}

@Composable
private fun TitleBar(
	title: String,
	maximized: Boolean,
	actions: List<DockAction>,
	onDrag: (Float, Float) -> Unit,
	onDragStopped: () -> Unit,
	onMinimize: () -> Unit,
	onToggleMaximize: () -> Unit,
	onDock: () -> Unit,
	onClose: () -> Unit,
) {
	val scheme = MaterialTheme.colorScheme
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(44.dp)
			.background(scheme.surfaceVariant)
			.pointerInput(Unit) {
				detectDragGestures(
					onDragEnd = onDragStopped,
					onDragCancel = onDragStopped,
				) { change, dragAmount ->
					change.consume()
					onDrag(dragAmount.x, dragAmount.y)
				}
			}
			.padding(horizontal = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Canvas(Modifier.size(18.dp)) { drawGrip(scheme.onSurfaceVariant) }
		Spacer(Modifier.width(6.dp))
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			color = scheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		actions.forEach { action -> ActionButton(action) }
		ChromeButton(onClick = onMinimize, description = "Minimize") { drawMinimize(it) }
		ChromeButton(
			onClick = onToggleMaximize,
			description = if (maximized) "Restore" else "Maximize",
		) { if (maximized) drawRestore(it) else drawMaximize(it) }
		ChromeButton(onClick = onDock, description = "Dock to editor") { drawDock(it) }
		ChromeButton(onClick = onClose, description = "Close") { drawClose(it) }
	}
}

@Composable
private fun ChromeButton(
	onClick: () -> Unit,
	description: String,
	draw: DrawScope.(Color) -> Unit,
) {
	val tint = MaterialTheme.colorScheme.onSurfaceVariant
	Box(
		modifier = Modifier
			.size(36.dp)
			.clip(RoundedCornerShape(8.dp))
			.clickable(onClick = onClick)
			.semantics { contentDescription = description },
		contentAlignment = Alignment.Center,
	) {
		Canvas(Modifier.size(18.dp)) { draw(tint) }
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(action: DockAction) {
	val scope = rememberCoroutineScope()
	var confirmed by remember { mutableStateOf(false) }
	val scheme = MaterialTheme.colorScheme
	val view = LocalView.current
	Box(
		modifier = Modifier
			.size(36.dp)
			.clip(RoundedCornerShape(8.dp))
			.combinedClickable(
				onLongClick = { action.onLongPress?.invoke(view) },
				onClick = {
					scope.launch {
						val confirm = runCatching { action.onInvoke() }.getOrDefault(false)
						if (confirm && action.confirmIconRes != null) {
							confirmed = true
							delay(ACTION_CONFIRM_MS)
							confirmed = false
						}
					}
				},
			)
			.semantics { contentDescription = action.label },
		contentAlignment = Alignment.Center,
	) {
		val iconRes = if (confirmed) action.confirmIconRes ?: action.iconRes else action.iconRes
		Crossfade(
			targetState = iconRes,
			animationSpec = tween(180),
			label = "floating-action-icon",
		) { res ->
			Icon(
				painter = painterResource(res),
				contentDescription = null,
				tint = if (confirmed) scheme.primary else scheme.onSurfaceVariant,
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

private const val ACTION_CONFIRM_MS = 1100L

@Composable
private fun ResizeHandle(
	onResize: (Float, Float) -> Unit,
	onResizeStopped: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val tint = MaterialTheme.colorScheme.onSurfaceVariant
	Box(
		modifier = modifier
			.size(28.dp)
			.pointerInput(Unit) {
				detectDragGestures(
					onDragEnd = onResizeStopped,
					onDragCancel = onResizeStopped,
				) { change, dragAmount ->
					change.consume()
					onResize(dragAmount.x, dragAmount.y)
				}
			},
		contentAlignment = Alignment.Center,
	) {
		Canvas(Modifier.size(16.dp)) { drawResizeGrip(tint) }
	}
}

private fun DrawScope.strokePx(): Float = 2.dp.toPx()

private fun DrawScope.drawGrip(color: Color) {
	val radius = 1.3.dp.toPx()
	val cols = floatArrayOf(size.width * 0.38f, size.width * 0.62f)
	val rows = floatArrayOf(size.height * 0.32f, size.height * 0.5f, size.height * 0.68f)
	for (x in cols) {
		for (y in rows) {
			drawCircle(color = color, radius = radius, center = Offset(x, y))
		}
	}
}

private fun DrawScope.drawMinimize(color: Color) {
	val y = size.height * 0.66f
	drawLine(color, Offset(size.width * 0.26f, y), Offset(size.width * 0.74f, y), strokePx(), StrokeCap.Round)
}

private fun DrawScope.drawMaximize(color: Color) {
	val pad = size.minDimension * 0.24f
	drawRect(
		color = color,
		topLeft = Offset(pad, pad),
		size = Size(size.width - 2 * pad, size.height - 2 * pad),
		style = Stroke(strokePx()),
	)
}

private fun DrawScope.drawRestore(color: Color) {
	val pad = size.minDimension * 0.2f
	val square = size.minDimension * 0.46f
	drawRect(color, Offset(size.width - pad - square, pad), Size(square, square), style = Stroke(strokePx()))
	drawRect(color, Offset(pad, size.height - pad - square), Size(square, square), style = Stroke(strokePx()))
}

private fun DrawScope.drawDock(color: Color) {
	val w = size.width
	val h = size.height
	val stroke = strokePx()
	drawLine(color, Offset(w * 0.26f, h * 0.78f), Offset(w * 0.74f, h * 0.78f), stroke, StrokeCap.Round)
	drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.6f), stroke, StrokeCap.Round)
	drawLine(color, Offset(w * 0.5f, h * 0.6f), Offset(w * 0.37f, h * 0.46f), stroke, StrokeCap.Round)
	drawLine(color, Offset(w * 0.5f, h * 0.6f), Offset(w * 0.63f, h * 0.46f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawClose(color: Color) {
	val pad = size.minDimension * 0.28f
	val stroke = strokePx()
	drawLine(color, Offset(pad, pad), Offset(size.width - pad, size.height - pad), stroke, StrokeCap.Round)
	drawLine(color, Offset(size.width - pad, pad), Offset(pad, size.height - pad), stroke, StrokeCap.Round)
}

private fun DrawScope.drawResizeGrip(color: Color) {
	val w = size.width
	val h = size.height
	val stroke = 1.5.dp.toPx()
	drawLine(color, Offset(w * 0.95f, h * 0.45f), Offset(w * 0.45f, h * 0.95f), stroke, StrokeCap.Round)
	drawLine(color, Offset(w * 0.95f, h * 0.7f), Offset(w * 0.7f, h * 0.95f), stroke, StrokeCap.Round)
}
