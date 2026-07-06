package org.appdevforall.cotg.profiler.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.appdevforall.cotg.profiler.cpu.CpuSample
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A lightweight live CPU-usage line graph drawn with a Compose [Canvas] (no charting dependency).
 *
 * The Y axis auto-scales to at least 100% and grows with the observed maximum, with gridlines and
 * percentage tick labels; the X axis labels elapsed time. Each sample is plotted as a dot on the
 * connecting line (thinned out when they would crowd). Tapping a point selects it: a crosshair and
 * an emphasized marker are drawn and a tooltip shows that sample's CPU% and elapsed time.
 */
@Composable
fun CpuUsageGraph(
	samples: List<CpuSample>,
	modifier: Modifier = Modifier,
) {
	var selectedIndex by remember { mutableStateOf<Int?>(null) }
	CpuUsageGraphContent(
		samples = samples,
		selectedIndex = selectedIndex?.takeIf { it in samples.indices },
		onSelectIndex = { selectedIndex = it },
		modifier = modifier,
	)
}

@Composable
private fun CpuUsageGraphContent(
	samples: List<CpuSample>,
	selectedIndex: Int?,
	onSelectIndex: (Int?) -> Unit,
	modifier: Modifier = Modifier,
) {
	val lineColor = MaterialTheme.colorScheme.primary
	val fillColor = lineColor.copy(alpha = 0.12f)
	val axisFaint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
	val axisStrong = MaterialTheme.colorScheme.outlineVariant
	val crosshairColor = lineColor.copy(alpha = 0.5f)
	val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

	val density = LocalDensity.current
	val leftGutterPx = with(density) { 44.dp.toPx() }
	val bottomGutterPx = with(density) { 18.dp.toPx() }
	val labelGapPx = with(density) { 6.dp.toPx() }
	val lineWidthPx = with(density) { 2.5.dp.toPx() }
	val dotRadiusPx = with(density) { 2.5.dp.toPx() }
	val ringRadiusPx = with(density) { 5.5.dp.toPx() }
	val ringStrokePx = with(density) { 1.5.dp.toPx() }
	val minDotSpacingPx = with(density) { 10.dp.toPx() }
	val hairlinePx = with(density) { Dimens.borderHairline.toPx() }
	val tooltipGapPx = with(density) { Dimens.paddingXs.toPx() }

	val textMeasurer = rememberTextMeasurer()
	val axisTextStyle = remember(labelColor) { TextStyle(color = labelColor, fontSize = 11.sp) }

	val observedMax = samples.maxOfOrNull { it.cpuPercent } ?: 0f
	// Round the scale up to the next 50% above 100% so the line doesn't hug the top edge.
	val scaleMax = max(100f, ceil(observedMax / 50f) * 50f)
	val latest = samples.lastOrNull()?.cpuPercent ?: 0f
	val lastIndex = samples.size - 1

	fun xOf(
		i: Int,
		w: Float,
	): Float = if (lastIndex <= 0) leftGutterPx else leftGutterPx + (w - leftGutterPx) * i / lastIndex

	fun yOf(
		percent: Float,
		h: Float,
	): Float = (h - bottomGutterPx) * (1f - percent.coerceIn(0f, scaleMax) / scaleMax)

	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)) {
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text(
				text = String.format(Locale.US, "CPU %.0f%%", latest),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = String.format(Locale.US, "peak %.0f%%", observedMax),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		var plotSize by remember { mutableStateOf(IntSize.Zero) }
		val currentSamples by rememberUpdatedState(samples)
		val currentOnSelect by rememberUpdatedState(onSelectIndex)

		Box(modifier = Modifier.fillMaxSize().onSizeChanged { plotSize = it }) {
			Canvas(
				modifier =
					Modifier
						.fillMaxSize()
						.pointerInput(Unit) {
							detectTapGestures(
								onTap = { offset ->
									val s = currentSamples
									val w = size.width.toFloat()
									val h = size.height.toFloat()
									val last = s.size - 1
									when {
										s.isEmpty() || offset.x < leftGutterPx || offset.y > h - bottomGutterPx ->
											currentOnSelect(null)

										last <= 0 -> currentOnSelect(0)

										else -> {
											val frac = ((offset.x - leftGutterPx) / (w - leftGutterPx)).coerceIn(0f, 1f)
											currentOnSelect((frac * last).roundToInt().coerceIn(0, last))
										}
									}
								},
							)
						},
			) {
				val w = size.width
				val h = size.height
				val plotBottom = h - bottomGutterPx

				// Y axis: gridlines + percentage labels in the left gutter.
				var pct = 0f
				while (pct <= scaleMax + 0.5f) {
					val gy = yOf(pct, h)
					val isHundred = pct == 100f
					drawLine(
						color = if (isHundred) axisStrong else axisFaint,
						start = Offset(leftGutterPx, gy),
						end = Offset(w, gy),
						strokeWidth = hairlinePx,
					)
					val label = textMeasurer.measure("${pct.roundToInt()}%", axisTextStyle)
					drawText(
						textLayoutResult = label,
						topLeft =
							Offset(
								x = (leftGutterPx - labelGapPx - label.size.width).coerceAtLeast(0f),
								y = (gy - label.size.height / 2f).coerceAtLeast(0f),
							),
					)
					pct += 50f
				}

				// X axis baseline + elapsed-time labels (start, middle, end).
				drawLine(axisStrong, Offset(leftGutterPx, plotBottom), Offset(w, plotBottom), strokeWidth = hairlinePx)
				if (samples.isNotEmpty()) {
					val tickIndices = if (lastIndex <= 0) listOf(0) else listOf(0, lastIndex / 2, lastIndex).distinct()
					for (i in tickIndices) {
						val label = textMeasurer.measure(formatElapsed(samples[i].elapsedMillis), axisTextStyle)
						val cx = xOf(i, w)
						val tx =
							when (i) {
								0 -> leftGutterPx
								lastIndex -> w - label.size.width
								else -> cx - label.size.width / 2f
							}
						drawText(
							textLayoutResult = label,
							topLeft = Offset(tx, plotBottom + (bottomGutterPx - label.size.height) / 2f),
						)
					}
				}

				when {
					samples.size >= 2 -> {
						val line =
							Path().apply {
								moveTo(xOf(0, w), yOf(samples[0].cpuPercent, h))
								for (i in 1..lastIndex) lineTo(xOf(i, w), yOf(samples[i].cpuPercent, h))
							}
						val area =
							Path().apply {
								addPath(line)
								lineTo(xOf(lastIndex, w), plotBottom)
								lineTo(xOf(0, w), plotBottom)
								close()
							}
						drawPath(area, fillColor)
						drawPath(line, lineColor, style = Stroke(width = lineWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))

						// Plot a dot at each sample, thinned so dots never crowd below ~minDotSpacing.
						val spacing = (w - leftGutterPx) / lastIndex
						val step = if (spacing <= 0f) lastIndex else max(1, ceil(minDotSpacingPx / spacing).toInt())
						var i = 0
						while (i <= lastIndex) {
							drawCircle(lineColor, dotRadiusPx, Offset(xOf(i, w), yOf(samples[i].cpuPercent, h)))
							i += step
						}
					}

					samples.size == 1 ->
						drawCircle(lineColor, dotRadiusPx, Offset(xOf(0, w), yOf(samples[0].cpuPercent, h)))
				}

				// Selection: crosshair + emphasized marker.
				if (selectedIndex != null && selectedIndex in samples.indices) {
					val sx = xOf(selectedIndex, w)
					val sy = yOf(samples[selectedIndex].cpuPercent, h)
					drawLine(crosshairColor, Offset(sx, 0f), Offset(sx, plotBottom), strokeWidth = hairlinePx)
					drawCircle(lineColor, ringRadiusPx, Offset(sx, sy), style = Stroke(ringStrokePx))
					drawCircle(lineColor, dotRadiusPx, Offset(sx, sy))
				}
			}

			val selected = selectedIndex?.takeIf { it in samples.indices }
			if (selected != null && plotSize != IntSize.Zero) {
				val w = plotSize.width.toFloat()
				val h = plotSize.height.toFloat()
				val sx = xOf(selected, w)
				val sy = yOf(samples[selected].cpuPercent, h)
				CpuSampleTooltip(
					sample = samples[selected],
					anchorX = sx,
					anchorY = sy,
					plotWidth = w,
					gapPx = tooltipGapPx,
				)
			}
		}
	}
}

@Composable
private fun CpuSampleTooltip(
	sample: CpuSample,
	anchorX: Float,
	anchorY: Float,
	plotWidth: Float,
	gapPx: Float,
) {
	var tipSize by remember { mutableStateOf(IntSize.Zero) }
	Column(
		modifier =
			Modifier
				.onSizeChanged { tipSize = it }
				.offset {
					val x =
						(anchorX - tipSize.width / 2f)
							.coerceIn(0f, (plotWidth - tipSize.width).coerceAtLeast(0f))
					val above = anchorY - tipSize.height - gapPx
					val y = if (above >= 0f) above else anchorY + gapPx
					IntOffset(x.roundToInt(), y.roundToInt())
				}.clip(RoundedCornerShape(Dimens.cornerSm))
				.background(MaterialTheme.colorScheme.surface)
				.border(Dimens.borderHairline, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Dimens.cornerSm))
				.padding(horizontal = Dimens.paddingSm, vertical = Dimens.paddingXs),
	) {
		Text(
			text = String.format(Locale.US, "%.1f%%", sample.cpuPercent),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Text(
			text = "at ${formatElapsed(sample.elapsedMillis)}",
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

private fun formatElapsed(ms: Long): String =
	if (ms < 60_000L) {
		String.format(Locale.US, "%.1f s", ms / 1000f)
	} else {
		String.format(Locale.US, "%d:%04.1f", ms / 60_000L, (ms % 60_000L) / 1000f)
	}

private fun previewSamples(count: Int): List<CpuSample> =
	(0 until count).map { i ->
		val base = 55f + 45f * sin(i * 0.5f)
		CpuSample(elapsedMillis = i * 500L, cpuPercent = (base + (i % 3) * 8f).coerceIn(2f, 135f))
	}

@Preview(name = "CPU usage", widthDp = 360, heightDp = 220)
@Composable
private fun CpuUsageGraphPreview() {
	ProfilerTheme {
		Surface {
			CpuUsageGraphContent(
				samples = previewSamples(24),
				selectedIndex = null,
				onSelectIndex = {},
				modifier = Modifier.fillMaxSize().padding(Dimens.paddingMd),
			)
		}
	}
}

@Preview(name = "CPU usage — selected", widthDp = 360, heightDp = 220)
@Composable
private fun CpuUsageGraphSelectedPreview() {
	ProfilerTheme {
		Surface {
			CpuUsageGraphContent(
				samples = previewSamples(24),
				selectedIndex = 16,
				onSelectIndex = {},
				modifier = Modifier.fillMaxSize().padding(Dimens.paddingMd),
			)
		}
	}
}

@Preview(name = "CPU usage — dense", widthDp = 360, heightDp = 220)
@Composable
private fun CpuUsageGraphDensePreview() {
	ProfilerTheme {
		Surface {
			CpuUsageGraphContent(
				samples = previewSamples(120),
				selectedIndex = null,
				onSelectIndex = {},
				modifier = Modifier.fillMaxSize().padding(Dimens.paddingMd),
			)
		}
	}
}
