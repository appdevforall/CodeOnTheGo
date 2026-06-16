package org.appdevforall.cotg.profiler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.appdevforall.cotg.profiler.ui.components.CellAlignment
import org.appdevforall.cotg.profiler.ui.components.ProfilerButton
import org.appdevforall.cotg.profiler.ui.components.ProfilerTable
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableColumn
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableRow
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

@Composable
fun ProfilerScreenView(
    onIntent: (ProfilerIntent) -> Unit,
    modifier: Modifier = Modifier,
    columns: List<ProfilerTableColumn> = emptyList(),
    rows: List<ProfilerTableRow> = emptyList(),
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
                if (rows.isEmpty()) {
                    EmptyState(message = stringResource(R.string.profiler_empty))
                } else {
                    ProfilerTable(
                        columns = columns,
                        rows = rows,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
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
private fun heapColumns(): List<ProfilerTableColumn> =
    listOf(
        ProfilerTableColumn(stringResource(R.string.profiler_col_class), 3f),
        ProfilerTableColumn(stringResource(R.string.profiler_col_count), 1f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_shallow), 1.4f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_retained), 1.4f, CellAlignment.End),
    )

@Composable
private fun cpuColumns(): List<ProfilerTableColumn> =
    listOf(
        ProfilerTableColumn(stringResource(R.string.profiler_col_symbol), 3f),
        ProfilerTableColumn(stringResource(R.string.profiler_col_self), 1.2f, CellAlignment.End),
        ProfilerTableColumn(stringResource(R.string.profiler_col_total), 1.2f, CellAlignment.End),
    )

@Preview(name = "Empty")
@Composable
private fun ProfilerScreenViewEmptyPreview() {
    ProfilerTheme {
        ProfilerScreenView(onIntent = {})
    }
}

@Preview(name = "Heap")
@Composable
private fun ProfilerScreenViewHeapPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            onIntent = {},
            columns = heapColumns(),
            rows = SampleProfileTables.HEAP_ROWS,
        )
    }
}

@Preview(name = "CPU")
@Composable
private fun ProfilerScreenViewCpuPreview() {
    ProfilerTheme {
        ProfilerScreenView(
            onIntent = {},
            columns = cpuColumns(),
            rows = SampleProfileTables.CPU_ROWS,
        )
    }
}
