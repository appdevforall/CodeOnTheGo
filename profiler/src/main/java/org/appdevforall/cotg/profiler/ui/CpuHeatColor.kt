package org.appdevforall.cotg.profiler.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.sqrt

// Heat ramp for coloring flamegraph frames by self (exclusive) time: cold/neutral for frames that
// barely run, ramping through orange to red for the real hotspots. Anchors are picked to read as a
// heat scale while staying in the app's warm palette; the cold anchor is theme-aware.
private val ColdDark = Color(0xFF39414F)
private val ColdLight = Color(0xFFDADFE8)
private val Warm = Color(0xFFEF8A2B)
private val Hot = Color(0xFFD83A2E)

/**
 * Fill color for a frame whose exclusive time is [selfMicros], relative to the hottest frame in the
 * tree ([maxSelfMicros]). Frames with no self time get the cold/neutral anchor so genuine hotspots
 * stand out. A `sqrt` curve keeps moderately-hot frames visibly warm rather than washing out
 * everything but the single peak.
 */
fun heatColor(selfMicros: Long, maxSelfMicros: Long, dark: Boolean): Color {
    val cold = if (dark) ColdDark else ColdLight
    if (maxSelfMicros <= 0L || selfMicros <= 0L) return cold
    val t = sqrt((selfMicros.toFloat() / maxSelfMicros.toFloat()).coerceIn(0f, 1f))
    return if (t <= 0.5f) lerp(cold, Warm, t / 0.5f) else lerp(Warm, Hot, (t - 0.5f) / 0.5f)
}
