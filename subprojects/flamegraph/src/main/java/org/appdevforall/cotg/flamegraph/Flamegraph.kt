package org.appdevforall.cotg.flamegraph

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.appdevforall.cotg.flamegraph.internal.FlameLayout
import org.appdevforall.cotg.flamegraph.internal.isInSubtree
import org.appdevforall.cotg.flamegraph.internal.LaidOutFrame
import org.appdevforall.cotg.flamegraph.internal.layoutFlame
import org.appdevforall.cotg.flamegraph.internal.resolveFocus
import org.appdevforall.cotg.flamegraph.model.FlameNode
import org.appdevforall.cotg.flamegraph.ui.theme.Dimens
import kotlin.math.floor

private const val MAX_SCALE = 64f

/**
 * Renders [root] as an interactive flamegraph / icicle.
 *
 * Tap a frame to highlight it and its subtree while everything else fades into the background (or
 * supply [onNodeClick] to override); tap empty space to clear. Pinch to zoom in horizontally and
 * drag to pan/scroll.
 *
 * @param onNodeClick null = built-in highlight; a non-null handler takes full control (no auto
 *   highlight). [onNodeLongClick] also highlights the long-pressed frame.
 */
@Composable
fun <T> Flamegraph(
    root: FlameNode<T>,
    modifier: Modifier = Modifier,
    state: FlamegraphState = rememberFlamegraphState(),
    colors: FlamegraphColors = FlamegraphDefaults.colors(),
    orientation: FlameOrientation = FlameOrientation.TopDown,
    rowHeight: Dp = FlamegraphDefaults.RowHeight,
    onNodeClick: ((FlameNode<T>) -> Unit)? = null,
    onNodeLongClick: ((FlameNode<T>) -> Unit)? = null,
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent(colors) },
) {
    val focusedKey = state.focusedKey
    val layout = remember(root, focusedKey, colors) {
        layoutFlame(root, focusedKey, colors.frameColor)
    }
    val focusLabel = remember(root, focusedKey) { resolveFocus(root, focusedKey).node.label }

    @Suppress("UNCHECKED_CAST")
    fun nodeOf(frame: LaidOutFrame): FlameNode<T> = layout.nodes[frame.nodeIndex] as FlameNode<T>

    FlamegraphCore(
        layout = layout,
        selectedKey = state.selectedKey,
        colors = colors,
        orientation = orientation,
        rowHeight = rowHeight,
        focusLabel = focusLabel,
        onFrameClick = { frame ->
            if (onNodeClick != null) onNodeClick(nodeOf(frame)) else state.select(frame.pathKey)
        },
        onFrameLongClick = { frame ->
            state.select(frame.pathKey)
            onNodeLongClick?.invoke(nodeOf(frame))
        },
        onBackgroundClick = { state.select(null) },
        emptyContent = emptyContent,
        modifier = modifier,
    )
}

@Composable
internal fun FlamegraphCore(
    layout: FlameLayout,
    selectedKey: String?,
    colors: FlamegraphColors,
    orientation: FlameOrientation,
    rowHeight: Dp,
    focusLabel: String,
    onFrameClick: (LaidOutFrame) -> Unit,
    onFrameLongClick: (LaidOutFrame) -> Unit,
    onBackgroundClick: () -> Unit,
    emptyContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { emptyContent() }
        return
    }

    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val gapPx = with(density) { Dimens.frameGap.toPx() }
    val minFrameWidthPx = with(density) { Dimens.minFrameWidth.toPx() }
    val minLabelWidthPx = with(density) { Dimens.minLabelWidth.toPx() }
    val labelPadPx = with(density) { Dimens.labelPaddingH.toPx() }
    val strokePx = with(density) { Dimens.selectedStroke.toPx() }

    val textMeasurer = rememberTextMeasurer()
    val styleOnLight = remember(colors) {
        TextStyle(color = colors.labelOnLight, fontFamily = FontFamily.Monospace, fontSize = Dimens.labelSize)
    }
    val styleOnDark = remember(colors) {
        TextStyle(color = colors.labelOnDark, fontFamily = FontFamily.Monospace, fontSize = Dimens.labelSize)
    }

    // Zoom/pan/scroll persist across selection; they reset only if the focus root itself changes.
    val transform = remember(layout.rootKey) { FlameTransform() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val contentHeightPx = layout.rowCount * rowHeightPx

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(layout) {
                detectTapGestures(
                    onTap = {
                        val frame = hitTest(it, canvasSize, transform, rowHeightPx, layout, orientation)
                        if (frame != null) onFrameClick(frame) else onBackgroundClick()
                    },
                    onLongPress = { hitTest(it, canvasSize, transform, rowHeightPx, layout, orientation)?.let(onFrameLongClick) },
                )
            }
            .pointerInput(layout) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val w = canvasSize.width
                    if (w <= 0f) return@detectTransformGestures
                    val oldScale = transform.scaleX
                    val newScale = (oldScale * zoom).coerceIn(1f, MAX_SCALE)
                    // Anchor the zoom at the centroid, then apply the horizontal drag.
                    val fractionAtCentroid = transform.panX + (centroid.x / w) / oldScale
                    var newPanX = fractionAtCentroid - (centroid.x / w) / newScale
                    newPanX -= (pan.x / w) / newScale
                    transform.scaleX = newScale
                    transform.panX = newPanX.coerceIn(0f, (1f - 1f / newScale).coerceAtLeast(0f))
                    val maxScroll = (contentHeightPx - canvasSize.height).coerceAtLeast(0f)
                    transform.scrollY = (transform.scrollY - pan.y).coerceIn(0f, maxScroll)
                }
            }
            .semantics { contentDescription = "Flamegraph focused on $focusLabel, ${layout.frames.size} frames" },
    ) {
        val w = size.width
        val h = size.height
        val scale = transform.scaleX
        val panX = transform.panX
        val scrollY = transform.scrollY

        val lo: Int
        val hi: Int
        when (orientation) {
            FlameOrientation.TopDown -> {
                lo = floor((scrollY - rowHeightPx) / rowHeightPx).toInt()
                hi = floor((scrollY + h) / rowHeightPx).toInt()
            }
            FlameOrientation.BottomUp -> {
                lo = layout.maxDepth - floor((scrollY + h) / rowHeightPx).toInt()
                hi = layout.maxDepth - floor((scrollY - rowHeightPx) / rowHeightPx).toInt()
            }
        }
        val firstDepth = lo.coerceAtLeast(0)
        val lastDepth = hi.coerceAtMost(layout.maxDepth)

        for (depth in firstDepth..lastDepth) {
            val yTop = yTopOf(depth, orientation, rowHeightPx, layout.maxDepth) - scrollY
            val row = layout.framesByDepth[depth]
            for (frame in row) {
                val screenX = (frame.xFraction - panX) * scale * w
                val frameW = frame.widthFraction * scale * w
                if (frameW < minFrameWidthPx) continue
                if (screenX > w || screenX + frameW < 0f) continue

                val left = screenX + gapPx
                val top = yTop + gapPx
                val drawW = frameW - gapPx
                val drawH = rowHeightPx - gapPx
                if (drawW <= 0f || drawH <= 0f) continue

                val highlighted = isInSubtree(frame.pathKey, selectedKey)
                val fill = if (highlighted) frame.color else frame.color.copy(alpha = colors.dimmedAlpha)
                drawRect(color = fill, topLeft = Offset(left, top), size = Size(drawW, drawH))

                if (frame.pathKey == selectedKey) {
                    drawRect(
                        color = colors.selectedStroke,
                        topLeft = Offset(left, top),
                        size = Size(drawW, drawH),
                        style = Stroke(strokePx),
                    )
                }

                if (highlighted && drawW >= minLabelWidthPx) {
                    val labelColor = colors.labelColorFor(frame.color)
                    val style = if (labelColor == colors.labelOnLight) styleOnLight else styleOnDark
                    val maxTextW = (drawW - 2f * labelPadPx).toInt().coerceAtLeast(0)
                    val result = textMeasurer.measure(
                        text = frame.label,
                        style = style,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = maxTextW),
                    )
                    val textY = top + (drawH - result.size.height) / 2f
                    clipRect(left = left + labelPadPx, top = top, right = left + drawW - labelPadPx, bottom = top + drawH) {
                        drawText(result, topLeft = Offset(left + labelPadPx, textY))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultEmptyContent(colors: FlamegraphColors) {
    Text(text = "No data", color = colors.emptyText, fontFamily = FontFamily.Monospace)
}

/** Mutable, snapshot-backed zoom/pan/scroll for one focus root. */
private class FlameTransform {
    var scaleX by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)
}

private fun yTopOf(depth: Int, orientation: FlameOrientation, rowHeightPx: Float, maxDepth: Int): Float =
    when (orientation) {
        FlameOrientation.TopDown -> depth * rowHeightPx
        FlameOrientation.BottomUp -> (maxDepth - depth) * rowHeightPx
    }

private fun hitTest(
    offset: Offset,
    canvasSize: Size,
    transform: FlameTransform,
    rowHeightPx: Float,
    layout: FlameLayout,
    orientation: FlameOrientation,
): LaidOutFrame? {
    val w = canvasSize.width
    if (w <= 0f || rowHeightPx <= 0f) return null
    val contentY = offset.y + transform.scrollY
    val rowIndex = floor(contentY / rowHeightPx).toInt()
    val depth = when (orientation) {
        FlameOrientation.TopDown -> rowIndex
        FlameOrientation.BottomUp -> layout.maxDepth - rowIndex
    }
    val xFraction = transform.panX + (offset.x / w) / transform.scaleX
    return layout.frameAt(depth, xFraction)
}

// ---- Previews -------------------------------------------------------------------------------

@Preview(name = "Flamegraph", widthDp = 360, heightDp = 240)
@Composable
private fun FlamegraphPreview() {
    PreviewScaffold { Flamegraph(root = SampleFlameData.cpu, modifier = Modifier.fillMaxSize()) }
}

@Preview(name = "Flamegraph dark", widthDp = 360, heightDp = 240, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FlamegraphDarkPreview() {
    PreviewScaffold(dark = true) { Flamegraph(root = SampleFlameData.cpu, modifier = Modifier.fillMaxSize()) }
}

@Preview(name = "Bottom-up", widthDp = 360, heightDp = 240)
@Composable
private fun FlamegraphBottomUpPreview() {
    PreviewScaffold {
        Flamegraph(root = SampleFlameData.cpu, orientation = FlameOrientation.BottomUp, modifier = Modifier.fillMaxSize())
    }
}

@Preview(name = "Deep tree", widthDp = 360, heightDp = 240)
@Composable
private fun FlamegraphDeepPreview() {
    PreviewScaffold { Flamegraph(root = SampleFlameData.deep, modifier = Modifier.fillMaxSize()) }
}

@Preview(name = "Many tiny frames", widthDp = 360, heightDp = 120)
@Composable
private fun FlamegraphWidePreview() {
    PreviewScaffold { Flamegraph(root = SampleFlameData.wide, modifier = Modifier.fillMaxSize()) }
}

@Preview(name = "Empty", widthDp = 360, heightDp = 120)
@Composable
private fun FlamegraphEmptyPreview() {
    PreviewScaffold { Flamegraph(root = SampleFlameData.empty, modifier = Modifier.fillMaxSize()) }
}

@Preview(name = "Highlighted subtree", widthDp = 360, heightDp = 240)
@Composable
private fun FlamegraphSelectedPreview() {
    val state = rememberFlamegraphState().apply { select("0/0/0/0") }
    PreviewScaffold { Flamegraph(root = SampleFlameData.cpu, state = state, modifier = Modifier.fillMaxSize()) }
}

@Composable
internal fun PreviewScaffold(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface { Column(Modifier.fillMaxWidth().height(240.dp)) { content() } }
    }
}
