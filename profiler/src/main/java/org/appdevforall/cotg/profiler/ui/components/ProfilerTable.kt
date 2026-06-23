package org.appdevforall.cotg.profiler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.appdevforall.cotg.profiler.ui.theme.Dimens
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

enum class CellAlignment { Start, End }

@Immutable
data class ProfilerTableColumn(
    val title: String,
    /** Share of the *extra* width when the table fits the viewport (see [ProfilerTable]). */
    val weight: Float,
    val alignment: CellAlignment = CellAlignment.Start,
    /** Floor width; columns shrink no further than this — the table scrolls horizontally instead. */
    val minWidth: Dp = Dimens.tableColMinWidth,
)

@Immutable
data class ProfilerTableRow(
    val id: String,
    val cells: List<String>,
)

/**
 * A simple table. Columns size to fill the viewport when they fit (extra space split by [weight]),
 * but never shrink below their [minWidth][ProfilerTableColumn.minWidth] — once the columns no longer
 * fit, the whole table (header + rows, kept aligned by a shared scroll state) scrolls horizontally.
 * This keeps long values (e.g. fully-qualified class names) and many-column tables readable.
 */
@Composable
fun ProfilerTable(
    columns: List<ProfilerTableColumn>,
    rows: List<ProfilerTableRow>,
    modifier: Modifier = Modifier,
    emptyMessage: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.cornerSm)
    val spacing = Dimens.paddingSm
    val horizontalPadding = Dimens.paddingMd

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .border(Dimens.borderHairline, colors.outlineVariant, shape),
    ) {
        val cellWidths = columnWidths(columns, maxWidth, spacing, horizontalPadding)
        // Total content width: the cells, the gaps between them, and the row's horizontal padding.
        val rowWidth = cellWidths.fold(0.dp) { acc, w -> acc + w } +
            spacing * (columns.size - 1).coerceAtLeast(0) + horizontalPadding * 2

        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
        ) {
            HeaderRow(
                columns = columns,
                cellWidths = cellWidths,
                rowWidth = rowWidth,
                spacing = spacing,
                horizontalPadding = horizontalPadding,
                color = colors.surfaceVariant,
                textColor = colors.onSurfaceVariant,
            )
            HorizontalDivider(
                modifier = Modifier.width(rowWidth),
                thickness = Dimens.borderHairline,
                color = colors.outlineVariant,
            )

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .width(rowWidth)
                        .weight(1f)
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
                LazyColumn(
                    modifier = Modifier
                        .width(rowWidth)
                        .weight(1f)
                        .background(colors.surface),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        DataRow(
                            columns = columns,
                            cellWidths = cellWidths,
                            rowWidth = rowWidth,
                            spacing = spacing,
                            horizontalPadding = horizontalPadding,
                            cells = row.cells,
                            textColor = colors.onSurface,
                        )
                        if (index < rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.width(rowWidth),
                                thickness = Dimens.borderHairline,
                                color = colors.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resolves each column's width: when the columns fit [viewport], the leftover space is shared by
 * [ProfilerTableColumn.weight] (so the table fills the width as before); otherwise every column sits
 * at its [ProfilerTableColumn.minWidth] and the table is wider than the viewport (scrolls).
 */
private fun columnWidths(
    columns: List<ProfilerTableColumn>,
    viewport: Dp,
    spacing: Dp,
    horizontalPadding: Dp,
): List<Dp> {
    val cellsMin = columns.fold(0.dp) { acc, c -> acc + c.minWidth }
    val chrome = spacing * (columns.size - 1).coerceAtLeast(0) + horizontalPadding * 2
    val extra = viewport - cellsMin - chrome
    if (extra <= 0.dp) return columns.map { it.minWidth }

    val totalWeight = columns.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
    return columns.map { it.minWidth + extra * (it.weight / totalWeight) }
}

@Composable
private fun HeaderRow(
    columns: List<ProfilerTableColumn>,
    cellWidths: List<Dp>,
    rowWidth: Dp,
    spacing: Dp,
    horizontalPadding: Dp,
    color: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .width(rowWidth)
            .background(color)
            .padding(horizontal = horizontalPadding, vertical = Dimens.paddingSm),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        columns.forEachIndexed { index, column ->
            TableCell(
                text = column.title,
                width = cellWidths[index],
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
    cellWidths: List<Dp>,
    rowWidth: Dp,
    spacing: Dp,
    horizontalPadding: Dp,
    cells: List<String>,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .width(rowWidth)
            .padding(horizontal = horizontalPadding, vertical = Dimens.paddingSm),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        columns.forEachIndexed { index, column ->
            TableCell(
                text = cells.getOrElse(index) { "" },
                width = cellWidths[index],
                alignment = column.alignment,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                monospace = column.alignment == CellAlignment.End,
            )
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    alignment: CellAlignment,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    monospace: Boolean,
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
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
                ProfilerTableColumn("Class", 3f, minWidth = 220.dp),
                ProfilerTableColumn("Count", 1f, CellAlignment.End, minWidth = 100.dp),
                ProfilerTableColumn("Shallow Size", 1f, CellAlignment.End, minWidth = 110.dp),
                ProfilerTableColumn("Retained", 1.4f, CellAlignment.End, minWidth = 110.dp),
            ),
            rows = listOf(
                ProfilerTableRow("1", listOf("byte[]", "1,284", "4.2 MB", "4.2 MB")),
                ProfilerTableRow("2", listOf("java.lang.String", "9,210", "221 KB", "1.1 MB")),
                ProfilerTableRow("3", listOf("android.graphics.Bitmap", "42", "3.9 KB", "3.1 MB")),
            ),
        )
    }
}
