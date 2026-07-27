package org.appdevforall.cotg.flamegraph

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Colours used to paint a flamegraph. Everything is derived from the consumer's [MaterialTheme] via
 * [FlamegraphDefaults.colors], so the component inherits the host app's light/dark theme.
 *
 * @param frameColor fill for a frame given its label and depth. The default is a stable hash into a
 *   warm "flame" hue band, so adjacent frames are distinguishable yet cohesive (a single hue family,
 *   flat fills, no gradients). A node's own [org.appdevforall.cotg.flamegraph.model.FlameNode.color]
 *   overrides this.
 * @param separator drawn in the hairline gap between frames (typically the surface colour).
 */
@Immutable
class FlamegraphColors(
	val frameColor: (label: String, depth: Int) -> Color,
	val selectedStroke: Color,
	val separator: Color,
	val background: Color,
	val emptyText: Color,
	val labelOnLight: Color,
	val labelOnDark: Color,
	/** Opacity applied to frames outside the selected subtree (1 = no fade). */
	val dimmedAlpha: Float,
) {
	/** High-contrast label colour for text drawn on top of [fill]. */
	fun labelColorFor(fill: Color): Color = if (luminance(fill) > 0.55f) labelOnLight else labelOnDark
}

object FlamegraphDefaults {
	val RowHeight: Dp = 22.dp

	@Composable
	fun colors(
		selectedStroke: Color = MaterialTheme.colorScheme.primary,
		separator: Color = MaterialTheme.colorScheme.surface,
		background: Color = MaterialTheme.colorScheme.surface,
		emptyText: Color = MaterialTheme.colorScheme.onSurfaceVariant,
		dimmedAlpha: Float = 0.5f,
	): FlamegraphColors {
		val dark = luminance(MaterialTheme.colorScheme.surface) < 0.5f
		return remember(selectedStroke, separator, background, emptyText, dimmedAlpha, dark) {
			FlamegraphColors(
				frameColor = { label, _ -> warmFrameColor(label, dark) },
				selectedStroke = selectedStroke,
				separator = separator,
				background = background,
				emptyText = emptyText,
				labelOnLight = Color(0xFF101010),
				labelOnDark = Color(0xFFF4F4F4),
				dimmedAlpha = dimmedAlpha,
			)
		}
	}
}

/** Stable hash of [label] into the warm flame band (~18°–46° hue), tuned for light/dark. */
private fun warmFrameColor(
	label: String,
	dark: Boolean,
): Color {
	val normalized = (label.hashCode().toLong() and 0xFFFFFFFFL).toFloat() / 0xFFFFFFFFL.toFloat()
	val hue = 18f + normalized * 28f
	val saturation = if (dark) 0.55f else 0.68f
	val lightness = if (dark) 0.42f else 0.58f
	return hslToColor(hue, saturation, lightness)
}

private fun hslToColor(
	hue: Float,
	saturation: Float,
	lightness: Float,
): Color {
	val c = (1f - abs(2f * lightness - 1f)) * saturation
	val hp = hue / 60f
	val x = c * (1f - abs(hp % 2f - 1f))
	val (r, g, b) =
		when {
			hp < 1f -> Triple(c, x, 0f)
			hp < 2f -> Triple(x, c, 0f)
			hp < 3f -> Triple(0f, c, x)
			hp < 4f -> Triple(0f, x, c)
			hp < 5f -> Triple(x, 0f, c)
			else -> Triple(c, 0f, x)
		}
	val m = lightness - c / 2f
	return Color(r + m, g + m, b + m)
}

private fun luminance(color: Color): Float = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
