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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.itsaky.androidide.profiler.R
import org.appdevforall.cotg.profiler.ProfilerIntent
import org.appdevforall.cotg.profiler.ProfilerIntent.CpuHotspot
import org.appdevforall.cotg.profiler.ProfilerIntent.DumpHeap
import org.appdevforall.cotg.profiler.ProfilerIntent.SelectProcess
import org.appdevforall.cotg.profiler.ProfilerMode
import org.appdevforall.cotg.profiler.ProfilerUiState
import org.appdevforall.cotg.profiler.ui.components.CellAlignment
import org.appdevforall.cotg.profiler.ui.components.ProcessPicker
import org.appdevforall.cotg.profiler.ui.components.ProfilerButton
import org.appdevforall.cotg.profiler.ui.components.ProfilerTable
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableColumn
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

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

                    is ProfilerUiState.Results ->
                        ProfilerTable(
                            columns = columnsFor(state.mode),
                            rows = state.rows,
                            modifier = Modifier.fillMaxSize(),
                        )

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
        ProfilerTableColumn(stringResource(R.string.profiler_col_symbol), 3f),
        ProfilerTableColumn(stringResource(R.string.profiler_col_self), 1.2f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_total), 1.2f, CellAlignment.End),
    )

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

@Preview(name = "CPU results")
@Composable
private fun ProfilerScreenCpuPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            state = ProfilerUiState.Results(
                mode = ProfilerMode.Cpu,
                process = SampleProfileTables.SAMPLE_PROCESSES.first(),
                rows = SampleProfileTables.CPU_ROWS,
            ),
            onIntent = {},
        )
    }
}
