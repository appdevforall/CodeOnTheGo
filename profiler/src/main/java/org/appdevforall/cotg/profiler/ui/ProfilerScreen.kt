package org.appdevforall.cotg.profiler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.profiler.R
import org.appdevforall.cotg.flamegraph.FlameOrientation
import org.appdevforall.cotg.flamegraph.Flamegraph
import org.appdevforall.cotg.flamegraph.rememberFlamegraphState
import org.appdevforall.cotg.profiler.ProfilerIntent
import org.appdevforall.cotg.profiler.ProfilerMode
import org.appdevforall.cotg.profiler.ProfilerReport
import org.appdevforall.cotg.profiler.ProfilerUiState
import org.appdevforall.cotg.profiler.cpu.CpuCallNode
import org.appdevforall.cotg.profiler.cpu.CpuMethodRow
import org.appdevforall.cotg.profiler.cpu.CpuProfile
import org.appdevforall.cotg.profiler.cpu.CpuSample
import org.appdevforall.cotg.profiler.cpu.collapseSystemFrames
import org.appdevforall.cotg.profiler.cpu.maxSelfMicros
import org.appdevforall.cotg.profiler.cpu.nodeAtPath
import org.appdevforall.cotg.profiler.cpu.pathLabels
import org.appdevforall.cotg.profiler.cpu.toFlameNode
import org.appdevforall.cotg.profiler.heap.HeapMetric
import org.appdevforall.cotg.profiler.heap.HeapObjectNode
import org.appdevforall.cotg.profiler.heap.HeapProfile
import org.appdevforall.cotg.profiler.heap.collapseFrameworkClasses
import org.appdevforall.cotg.profiler.heap.maxShallowBytes
import org.appdevforall.cotg.profiler.heap.nodeAtPath as heapNodeAtPath
import org.appdevforall.cotg.profiler.heap.pathLabels as heapPathLabels
import org.appdevforall.cotg.profiler.heap.toFlameNode as toHeapFlameNode
import org.appdevforall.cotg.profiler.ui.components.CellAlignment
import org.appdevforall.cotg.profiler.ui.components.CpuUsageGraph
import org.appdevforall.cotg.profiler.ui.components.ProcessPicker
import org.appdevforall.cotg.profiler.ui.components.ProfilerButton
import org.appdevforall.cotg.profiler.ui.components.ProfilerTable
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableColumn
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme
import java.util.Locale

@Composable
fun ProfilerScreenView(
    state: ProfilerUiState,
    onIntent: (ProfilerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.paddingMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd),
        ) {
            ProfilerControls(state = state, onIntent = onIntent)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ProfilerContent(state = state, onIntent = onIntent)
            }
        }
    }
}

/**
 * The top control row. Start buttons appear only in [ProfilerUiState.Idle]; every other state shows
 * a single button — "Cancel"/"Stop" for an in-flight run, "Start another profile" once finished —
 * so a second profiling task can't be started while one is running.
 */
@Composable
private fun ColumnScope.ProfilerControls(
    state: ProfilerUiState,
    onIntent: (ProfilerIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
    ) {
        when (state) {
            ProfilerUiState.Idle -> {
                ProfilerButton(
                    onClick = { onIntent(ProfilerIntent.DumpHeap) },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.profiler_dump_heap),
                )
                ProfilerButton(
                    onClick = { onIntent(ProfilerIntent.CpuHotspot) },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.profiler_cpu_hotspots),
                )
            }

            is ProfilerUiState.ChooseProcess ->
                ControlButton(R.string.profiler_cancel) { onIntent(ProfilerIntent.Reset) }

            is ProfilerUiState.Profiling.HeapDump ->
                ControlButton(R.string.profiler_cancel) { onIntent(ProfilerIntent.StopProfiling) }

            is ProfilerUiState.Profiling.CpuSampling ->
                // While sampling the button stops & generates the report; while the report is being
                // generated the same button cancels it (see ProfilerViewModel.onStopProfiling).
                ControlButton(
                    if (state.finalizing) R.string.profiler_cancel else R.string.profiler_cpu_stop,
                ) { onIntent(ProfilerIntent.StopProfiling) }

            is ProfilerUiState.Completed, is ProfilerUiState.Failed ->
                ControlButton(R.string.profiler_start_another) { onIntent(ProfilerIntent.Reset) }
        }
    }
}

@Composable
private fun ControlButton(@androidx.annotation.StringRes labelRes: Int, onClick: () -> Unit) {
    ProfilerButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(labelRes),
    )
}

@Composable
private fun ProfilerContent(
    state: ProfilerUiState,
    onIntent: (ProfilerIntent) -> Unit,
) {
    when (state) {
        ProfilerUiState.Idle ->
            CenteredMessage(stringResource(R.string.profiler_empty))

        is ProfilerUiState.ChooseProcess ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
            ) {
                Text(
                    text = stringResource(R.string.profiler_select_process),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProcessPicker(
                    processes = state.processes,
                    onSelect = { onIntent(ProfilerIntent.SelectProcess(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    emptyMessage = stringResource(R.string.profiler_no_processes),
                )
            }

        is ProfilerUiState.Profiling.HeapDump ->
            Loading(stringResource(R.string.profiler_running, state.process.label))

        is ProfilerUiState.Profiling.CpuSampling ->
            if (state.finalizing) {
                Loading(stringResource(R.string.profiler_cpu_processing))
            } else {
                CpuUsageGraph(samples = state.samples, modifier = Modifier.fillMaxSize())
            }

        is ProfilerUiState.Completed ->
            when (val report = state.report) {
                is ProfilerReport.HeapDump ->
                    HeapResultView(profile = report.profile, modifier = Modifier.fillMaxSize())

                is ProfilerReport.CpuSampling ->
                    CpuResultView(profile = report.profile, modifier = Modifier.fillMaxSize())
            }

        is ProfilerUiState.Failed ->
            CenteredMessage(
                message = state.message,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/**
 * The CPU result: a single interactive flamegraph. Frames are heat-colored by self (exclusive) time
 * so hotspots pop; tapping a frame fills the [CpuDetailStrip] with its self/total time and call path.
 * Two toggles keep it compact and useful: hide framework/runtime frames (collapsing them so app code
 * nested beneath stays visible) and flip the graph direction (top-down icicle ↔ bottom-up flame).
 */
@Composable
private fun CpuResultView(profile: CpuProfile, modifier: Modifier = Modifier) {
    var hideSystem by rememberSaveable { mutableStateOf(false) }
    var bottomUp by rememberSaveable { mutableStateOf(false) }
    val flamegraphState = rememberFlamegraphState()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val displayRoot = remember(profile, hideSystem) {
        if (hideSystem) profile.root.collapseSystemFrames() else profile.root
    }
    val maxSelf = remember(displayRoot) { displayRoot.maxSelfMicros() }
    val flameRoot = remember(displayRoot, maxSelf, dark) {
        displayRoot.toFlameNode { heatColor(it.selfMicros, maxSelf, dark) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
        ) {
            FilterChip(
                selected = hideSystem,
                onClick = {
                    hideSystem = !hideSystem
                    // The path keys are indices into the (now different) tree — drop the selection.
                    flamegraphState.reset()
                },
                label = { Text(stringResource(R.string.profiler_hide_system_frames)) },
            )
            FilterChip(
                selected = bottomUp,
                onClick = { bottomUp = !bottomUp },
                label = { Text(stringResource(R.string.profiler_orientation_toggle)) },
            )
        }

        Flamegraph(
            root = flameRoot,
            state = flamegraphState,
            orientation = if (bottomUp) FlameOrientation.BottomUp else FlameOrientation.TopDown,
            rowHeight = 18.dp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        CpuDetailStrip(
            node = displayRoot.nodeAtPath(flamegraphState.selectedKey),
            pathLabels = displayRoot.pathLabels(flamegraphState.selectedKey),
            totalMicros = profile.totalMicros,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Bottom strip showing the tapped frame's exclusive/inclusive time (relative to the whole profile)
 * and its root→frame call path. Shows a hint when nothing is selected.
 */
@Composable
private fun CpuDetailStrip(
    node: CpuCallNode?,
    pathLabels: List<String>,
    totalMicros: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Dimens.cornerSm),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingXs),
        ) {
            if (node == null) {
                Text(
                    text = stringResource(R.string.profiler_cpu_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                text = node.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.profiler_cpu_self,
                    formatMicros(node.selfMicros),
                    formatPercent(percentOf(node.selfMicros, totalMicros)),
                ) + "  ·  " + stringResource(
                    R.string.profiler_cpu_total,
                    formatMicros(node.totalMicros),
                    formatPercent(percentOf(node.totalMicros, totalMicros)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pathLabels.size > 1) {
                Text(
                    text = pathLabels.joinToString("  ›  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The heap result: an interactive flamegraph over the object **dominator tree**. A frame's width is
 * the memory it retains (or the object count it retains, via the metric toggle); frames are
 * heat-colored by shallow (self) size so the objects that themselves occupy the most memory pop —
 * mirroring [CpuResultView]'s inclusive-width + self-heat model. Tapping a frame fills the
 * [HeapDetailStrip]. Toggles: switch the width metric, hide framework/runtime classes (collapsing
 * them so app objects retained beneath stay visible), flip the graph direction, and drop to the flat
 * class-list table.
 */
@Composable
private fun HeapResultView(profile: HeapProfile, modifier: Modifier = Modifier) {
    var metricBytes by rememberSaveable { mutableStateOf(true) }
    var hideFramework by rememberSaveable { mutableStateOf(false) }
    var bottomUp by rememberSaveable { mutableStateOf(false) }
    var showTable by rememberSaveable { mutableStateOf(false) }
    val flamegraphState = rememberFlamegraphState()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val metric = if (metricBytes) HeapMetric.RetainedBytes else HeapMetric.InstanceCount

    val displayRoot = remember(profile, hideFramework) {
        if (hideFramework) profile.root.collapseFrameworkClasses() else profile.root
    }
    val maxShallow = remember(displayRoot) { displayRoot.maxShallowBytes() }
    val flameRoot = remember(displayRoot, metric, maxShallow, dark) {
        displayRoot.toHeapFlameNode(metric) { heatColor(it.shallowBytes, maxShallow, dark) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
        ) {
            // Width metric is a two-option selector; it only re-weights the same tree, so the
            // selection path keys stay valid and the flamegraph selection is preserved.
            FilterChip(
                selected = !showTable && metricBytes,
                onClick = { showTable = false; metricBytes = true },
                label = { Text(stringResource(R.string.profiler_heap_metric_retained)) },
            )
            FilterChip(
                selected = !showTable && !metricBytes,
                onClick = { showTable = false; metricBytes = false },
                label = { Text(stringResource(R.string.profiler_heap_metric_count)) },
            )
            FilterChip(
                selected = showTable,
                onClick = { showTable = !showTable },
                label = { Text(stringResource(R.string.profiler_heap_view_table)) },
            )
        }

        if (!showTable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
            ) {
                FilterChip(
                    selected = hideFramework,
                    onClick = {
                        hideFramework = !hideFramework
                        // The path keys index into the (now different) tree — drop the selection.
                        flamegraphState.reset()
                    },
                    label = { Text(stringResource(R.string.profiler_hide_framework_classes)) },
                )
                FilterChip(
                    selected = bottomUp,
                    onClick = { bottomUp = !bottomUp },
                    label = { Text(stringResource(R.string.profiler_orientation_toggle)) },
                )
            }
        }

        if (showTable) {
            ProfilerTable(
                columns = heapColumns(),
                rows = profile.rows,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            Flamegraph(
                root = flameRoot,
                state = flamegraphState,
                orientation = if (bottomUp) FlameOrientation.BottomUp else FlameOrientation.TopDown,
                rowHeight = 18.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            HeapDetailStrip(
                node = displayRoot.heapNodeAtPath(flamegraphState.selectedKey),
                pathLabels = displayRoot.heapPathLabels(flamegraphState.selectedKey),
                totalRetainedBytes = profile.totalRetainedBytes,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Bottom strip showing the tapped object's shallow (self) size, the memory/objects it retains
 * (with its share of the whole dump), and its root→object dominator path. Shows a hint when nothing
 * is selected.
 */
@Composable
private fun HeapDetailStrip(
    node: HeapObjectNode?,
    pathLabels: List<String>,
    totalRetainedBytes: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Dimens.cornerSm),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingXs),
        ) {
            if (node == null) {
                Text(
                    text = stringResource(R.string.profiler_heap_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                text = node.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.profiler_heap_shallow, formatBytes(node.shallowBytes)) +
                    "  ·  " + stringResource(
                        R.string.profiler_heap_retained,
                        formatBytes(node.retainedBytes),
                        formatPercent(percentOf(node.retainedBytes, totalRetainedBytes)),
                    ) +
                    "  ·  " + stringResource(R.string.profiler_heap_objects, formatCount(node.retainedCount)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pathLabels.size > 1) {
                Text(
                    text = pathLabels.joinToString("  ›  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(Dimens.paddingLg),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Loading(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// Shark exposes per-class count and shallow size via its public API (retained size relies on its
// internal dominator-tree types), so the runtime heap table shows Class / Count / Shallow.
@Composable
private fun heapColumns(): List<ProfilerTableColumn> =
    listOf(
        ProfilerTableColumn(stringResource(R.string.profiler_col_class), 3f),
        ProfilerTableColumn(stringResource(R.string.profiler_col_count), 1f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_shallow), 1.4f, CellAlignment.End),
    )

private fun formatMicros(micros: Long): String = String.format(Locale.US, "%,d", micros)

private fun formatPercent(percent: Float): String = String.format(Locale.US, "%.1f%%", percent)

private fun percentOf(part: Long, total: Long): Float =
    if (total > 0L) (part.toDouble() / total * 100.0).toFloat() else 0f

private fun formatCount(count: Long): String = String.format(Locale.US, "%,d", count)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

@Preview(name = "Idle")
@Composable
private fun ProfilerScreenIdlePreview() {
    ProfilerTheme {
        ProfilerScreenView(state = ProfilerUiState.Idle, onIntent = {})
    }
}

@Preview(name = "Choose process")
@Composable
private fun ProfilerScreenChooseProcessPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.ChooseProcess(
                mode = ProfilerMode.Heap,
                processes = SampleProfileTables.SAMPLE_PROCESSES.filter { it.debuggable },
            ),
            onIntent = {},
        )
    }
}

@Preview(name = "CPU sampling")
@Composable
private fun ProfilerScreenCpuSamplingPreview() {
    val samples = (0..20).map { CpuSample(it * 500L, 30f + (it % 5) * 12f) }
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.Profiling.CpuSampling(
                SampleProfileTables.SAMPLE_PROCESSES.first(),
                samples,
            ),
            onIntent = {},
        )
    }
}

@Preview(name = "Heap result")
@Composable
private fun ProfilerScreenHeapResultPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.Completed(
                process = SampleProfileTables.SAMPLE_PROCESSES.first(),
                report = ProfilerReport.HeapDump(SampleProfileTables.SAMPLE_HEAP_PROFILE),
            ),
            onIntent = {},
        )
    }
}

@Preview(name = "CPU result")
@Composable
private fun ProfilerScreenCpuResultPreview() {
    val profile = CpuProfile(
        root = CpuCallNode(
            "(root)", selfMicros = 0, totalMicros = 1_530,
            children = listOf(
                CpuCallNode(
                    "android.view.Choreographer.doFrame", selfMicros = 240, totalMicros = 1_142,
                    children = listOf(
                        CpuCallNode(
                            "android.view.View.draw", selfMicros = 214, totalMicros = 902,
                            children = listOf(
                                CpuCallNode("android.graphics.Canvas.drawPath", selfMicros = 688, totalMicros = 688, children = emptyList()),
                            ),
                        ),
                    ),
                ),
                CpuCallNode("libc.so nativePollOnce", selfMicros = 388, totalMicros = 388, children = emptyList()),
            ),
        ),
        totalMicros = 1_530,
        methods = listOf(
            CpuMethodRow("android.view.Choreographer.doFrame", 1_530, 100f, 1_048, 68.5f),
            CpuMethodRow("android.view.View.draw", 902, 59f, 688, 45f),
            CpuMethodRow("libc.so nativePollOnce", 388, 25.4f, 0, 0f),
        ),
    )
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.Completed(
                process = SampleProfileTables.SAMPLE_PROCESSES.first(),
                report = ProfilerReport.CpuSampling(profile),
            ),
            onIntent = {},
        )
    }
}

@Preview(name = "Failed (cancelled)")
@Composable
private fun ProfilerScreenFailedPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.Failed("Profiling was cancelled.", cancelled = true),
            onIntent = {},
        )
    }
}
