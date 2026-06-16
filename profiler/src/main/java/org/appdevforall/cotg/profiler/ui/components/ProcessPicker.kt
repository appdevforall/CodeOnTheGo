package org.appdevforall.cotg.profiler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.itsaky.androidide.profiler.R
import org.appdevforall.cotg.profiler.model.ProcessInfo
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

@Composable
fun ProcessPicker(
    processes: List<ProcessInfo>,
    onSelect: (ProcessInfo) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.cornerSm)

    Column(
        modifier = modifier
            .clip(shape)
            .border(Dimens.borderHairline, colors.outlineVariant, shape)
            .background(colors.surface),
    ) {
        if (processes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Dimens.paddingLg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(processes, key = { _, process -> process.pid }) { index, process ->
                    ProcessRow(process = process, onClick = { onSelect(process) })
                    if (index < processes.lastIndex) {
                        HorizontalDivider(
                            thickness = Dimens.borderHairline,
                            color = colors.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(
    process: ProcessInfo,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingXxs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = process.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.profiler_process_picker_pid,
                    process.packageName,
                    process.pid
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.paddingXxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (process.debuggable) CapabilityBadge(text = stringResource(R.string.profiler_process_picker_debuggable))
            if (process.profileable) CapabilityBadge(text = stringResource(R.string.profiler_process_picker_profileable))
        }
    }
}

@Composable
private fun CapabilityBadge(text: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.surfaceVariant)
            .padding(horizontal = Dimens.paddingXs, vertical = Dimens.paddingXxs),
    )
}

@Preview
@Composable
private fun ProcessPickerPreview() {
    ProfilerTheme {
        ProcessPicker(
            processes = listOf(
                ProcessInfo(1287, "com.example.app", "Example App", debuggable = true, profileable = true),
                ProcessInfo(1422, "com.example.app:service", "Example App (service)", debuggable = true, profileable = false),
                ProcessInfo(2051, "org.appdevforall.cotg", "Code on the Go", debuggable = false, profileable = true),
                ProcessInfo(3310, "com.example.game", "Example Game", debuggable = false, profileable = true),
            ),
            onSelect = {},
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.paddingMd),
        )
    }
}
