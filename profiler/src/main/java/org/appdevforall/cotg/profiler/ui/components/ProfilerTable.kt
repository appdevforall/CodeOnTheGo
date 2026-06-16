package org.appdevforall.cotg.profiler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

enum class CellAlignment { Start, End }

@Immutable
data class ProfilerTableColumn(
    val title: String,
    val weight: Float,
    val alignment: CellAlignment = CellAlignment.Start,
)

@Immutable
data class ProfilerTableRow(
    val id: String,
    val cells: List<String>,
)

@Composable
fun ProfilerTable(
    columns: List<ProfilerTableColumn>,
    rows: List<ProfilerTableRow>,
    modifier: Modifier = Modifier,
    emptyMessage: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.cornerSm)

    Column(
        modifier = modifier
            .clip(shape)
            .border(Dimens.borderHairline, colors.outlineVariant, shape),
    ) {
        HeaderRow(columns = columns, color = colors.surfaceVariant, textColor = colors.onSurfaceVariant)
        HorizontalDivider(thickness = Dimens.borderHairline, color = colors.outlineVariant)

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surface)
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
            LazyColumn(modifier = Modifier.background(colors.surface)) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    DataRow(
                        columns = columns,
                        cells = row.cells,
                        textColor = colors.onSurface,
                    )
                    if (index < rows.lastIndex) {
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
private fun HeaderRow(
    columns: List<ProfilerTableColumn>,
    color: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingSm),
        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
    ) {
        columns.forEach { column ->
            TableCell(
                text = column.title,
                weight = column.weight,
                alignment = column.alignment,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                monospace = false,
            )
        }
    }
}

@Composable
private fun DataRow(
    columns: List<ProfilerTableColumn>,
    cells: List<String>,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingSm),
        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
    ) {
        columns.forEachIndexed { index, column ->
            TableCell(
                text = cells.getOrElse(index) { "" },
                weight = column.weight,
                alignment = column.alignment,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                monospace = column.alignment == CellAlignment.End,
            )
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    alignment: CellAlignment,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    monospace: Boolean,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = if (monospace) style.copy(fontFamily = FontFamily.Monospace) else style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignment == CellAlignment.End) TextAlign.End else TextAlign.Start,
    )
}

@Preview
@Composable
private fun ProfilerTablePreview() {
    ProfilerTheme {
        ProfilerTable(
            columns = listOf(
                ProfilerTableColumn("Class", 3f),
                ProfilerTableColumn("Count", 1f, CellAlignment.End),
                ProfilerTableColumn("Shallow Size", 1f, CellAlignment.End),
                ProfilerTableColumn("Retained", 1.4f, CellAlignment.End),
            ),
            rows = listOf(
                ProfilerTableRow("1", listOf("byte[]", "1,284", "4.2 MB", "4.2 MB")),
                ProfilerTableRow("2", listOf("java.lang.String", "9,210", "221 KB", "1.1 MB")),
                ProfilerTableRow("3", listOf("android.graphics.Bitmap", "42", "3.9 KB", "3.1 MB")),
            ),
        )
    }
}
