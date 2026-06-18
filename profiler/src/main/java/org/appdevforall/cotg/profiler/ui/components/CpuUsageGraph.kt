package org.appdevforall.cotg.profiler.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import org.appdevforall.cotg.profiler.cpu.CpuSample
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * A lightweight live CPU-usage line graph drawn with a Compose [Canvas] (no charting dependency).
 * The Y axis auto-scales to at least 100% and grows with the observed maximum; the X axis is the
 * sample sequence (newest on the right).
 */
@Composable
fun CpuUsageGraph(
    samples: List<CpuSample>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.15f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant

    val observedMax = samples.maxOfOrNull { it.cpuPercent } ?: 0f
    // Round the scale up to the next 50% above 100% so the line doesn't hug the top edge.
    val scaleMax = max(100f, ceil(observedMax / 50f) * 50f)
    val latest = samples.lastOrNull()?.cpuPercent ?: 0f

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = String.format(Locale.US, "CPU %.0f%%", latest),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = String.format(Locale.US, "scale %.0f%%", scaleMax),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Baseline.
            drawLine(axisColor, Offset(0f, h), Offset(w, h), strokeWidth = 2f)
            // 100% reference line.
            val refY = h - (100f / scaleMax) * h
            drawLine(axisColor, Offset(0f, refY), Offset(w, refY), strokeWidth = 1f)

            if (samples.size < 2) return@Canvas

            val lastIndex = samples.size - 1
            fun x(i: Int) = w * i / lastIndex
            fun y(percent: Float) = h - (percent.coerceIn(0f, scaleMax) / scaleMax) * h

            val line = Path().apply {
                moveTo(x(0), y(samples[0].cpuPercent))
                for (i in 1..lastIndex) lineTo(x(i), y(samples[i].cpuPercent))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(area, fillColor)
            drawPath(line, lineColor, style = Stroke(width = 4f))
        }
    }
}
