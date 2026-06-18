package org.appdevforall.cotg.profiler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.itsaky.androidide.profiler.R
import org.appdevforall.cotg.flamegraph.Flamegraph
import org.appdevforall.cotg.flamegraph.rememberFlamegraphState
import org.appdevforall.cotg.profiler.ProfilerIntent
import org.appdevforall.cotg.profiler.ProfilerIntent.CpuHotspot
import org.appdevforall.cotg.profiler.ProfilerIntent.DumpHeap
import org.appdevforall.cotg.profiler.ProfilerIntent.SelectProcess
import org.appdevforall.cotg.profiler.ProfilerMode
import org.appdevforall.cotg.profiler.ProfilerUiState
import org.appdevforall.cotg.profiler.cpu.CpuCallNode
import org.appdevforall.cotg.profiler.cpu.CpuMethodRow
import org.appdevforall.cotg.profiler.cpu.CpuProfile
import org.appdevforall.cotg.profiler.cpu.CpuSample
import org.appdevforall.cotg.profiler.cpu.toFlameNode
import org.appdevforall.cotg.profiler.ui.components.CellAlignment
import org.appdevforall.cotg.profiler.ui.components.CpuUsageGraph
import org.appdevforall.cotg.profiler.ui.components.ProcessPicker
import org.appdevforall.cotg.profiler.ui.components.ProfilerButton
import org.appdevforall.cotg.profiler.ui.components.ProfilerTable
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableColumn
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableRow
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
            ) {
                ProfilerButton(
                    onClick = { onIntent(DumpHeap) },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.profiler_dump_heap),
                )
                ProfilerButton(
                    onClick = { onIntent(CpuHotspot) },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.profiler_cpu_hotspots),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (state) {
                    ProfilerUiState.Idle ->
                        Hint(message = stringResource(R.string.profiler_empty))

                    is ProfilerUiState.SelectingProcess ->
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
                                onSelect = { onIntent(SelectProcess(it)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                emptyMessage = stringResource(R.string.profiler_no_processes),
                            )
                        }

                    is ProfilerUiState.Running ->
                        Loading(message = stringResource(R.string.profiler_running, state.process.label))

                    is ProfilerUiState.Profiling ->
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd),
                        ) {
                            Text(
                                text = stringResource(R.string.profiler_cpu_recording, state.process.label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CpuUsageGraph(
                                samples = state.samples,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                            ProfilerButton(
                                onClick = { onIntent(ProfilerIntent.StopProfiling) },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.profiler_cpu_stop),
                            )
                        }

                    is ProfilerUiState.Processing ->
                        Loading(message = stringResource(R.string.profiler_cpu_processing))

                    is ProfilerUiState.Results ->
                        ProfilerTable(
                            columns = columnsFor(state.mode),
                            rows = state.rows,
                            modifier = Modifier.fillMaxSize(),
                        )

                    is ProfilerUiState.CpuResult ->
                        CpuResultTabs(profile = state.profile, modifier = Modifier.fillMaxSize())

                    is ProfilerUiState.Error ->
                        ErrorMessage(
                            message = state.message,
                            onDismiss = { onIntent(ProfilerIntent.Reset) },
                        )
                }
            }
        }
    }
}

@Composable
private fun CpuResultTabs(profile: CpuProfile, modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val flameRoot = remember(profile) { profile.root.toFlameNode() }
    val flamegraphState = rememberFlamegraphState()

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.profiler_tab_table)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.profiler_tab_flamegraph)) },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = Dimens.paddingMd),
        ) {
            when (selectedTab) {
                0 ->
                    ProfilerTable(
                        columns = cpuColumns(),
                        rows = cpuRows(profile),
                        modifier = Modifier.fillMaxSize(),
                    )

                else ->
                    Flamegraph(
                        root = flameRoot,
                        state = flamegraphState,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

@Composable
private fun Hint(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(Dimens.paddingLg),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ErrorMessage(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.paddingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onDismiss) {
            Text(text = stringResource(R.string.profiler_retry))
        }
    }
}

@Composable
private fun columnsFor(mode: ProfilerMode): List<ProfilerTableColumn> =
    when (mode) {
        ProfilerMode.Heap -> heapColumns()
        ProfilerMode.Cpu -> cpuColumns()
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

@Composable
private fun cpuColumns(): List<ProfilerTableColumn> =
    listOf(
        ProfilerTableColumn(stringResource(R.string.profiler_col_method), 3f),
        ProfilerTableColumn(stringResource(R.string.profiler_col_total_us), 1.3f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_total_pct), 1f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_children_us), 1.3f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_children_pct), 1f, CellAlignment.End),
    )

private fun cpuRows(profile: CpuProfile): List<ProfilerTableRow> =
    profile.methods.map { method ->
        ProfilerTableRow(
            id = method.name,
            cells = listOf(
                method.name,
                formatMicros(method.totalMicros),
                formatPercent(method.totalPercent),
                formatMicros(method.childrenMicros),
                formatPercent(method.childrenPercent),
            ),
        )
    }

private fun formatMicros(micros: Long): String = String.format(Locale.US, "%,d", micros)

private fun formatPercent(percent: Float): String = String.format(Locale.US, "%.1f%%", percent)

@Preview(name = "Idle")
@Composable
private fun ProfilerScreenIdlePreview() {
    ProfilerTheme {
        ProfilerScreenView(state = ProfilerUiState.Idle, onIntent = {})
    }
}

@Preview(name = "Select process")
@Composable
private fun ProfilerScreenSelectingPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.SelectingProcess(
                mode = ProfilerMode.Heap,
                processes = SampleProfileTables.SAMPLE_PROCESSES.filter { it.debuggable },
            ),
            onIntent = {},
        )
    }
}

@Preview(name = "Heap results")
@Composable
private fun ProfilerScreenHeapPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.Results(
                mode = ProfilerMode.Heap,
                process = SampleProfileTables.SAMPLE_PROCESSES.first(),
                rows = SampleProfileTables.HEAP_ROWS,
            ),
            onIntent = {},
        )
    }
}

@Preview(name = "CPU profiling")
@Composable
private fun ProfilerScreenProfilingPreview() {
    val samples = (0..20).map { CpuSample(it * 500L, 30f + (it % 5) * 12f) }
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.Profiling(SampleProfileTables.SAMPLE_PROCESSES.first(), samples),
            onIntent = {},
        )
    }
}

@Preview(name = "CPU results")
@Composable
private fun ProfilerScreenCpuPreview() {
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
            state = ProfilerUiState.CpuResult(SampleProfileTables.SAMPLE_PROCESSES.first(), profile),
            onIntent = {},
        )
    }
}
