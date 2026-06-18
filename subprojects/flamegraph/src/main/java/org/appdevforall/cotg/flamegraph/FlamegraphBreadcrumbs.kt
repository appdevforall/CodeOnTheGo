package org.appdevforall.cotg.flamegraph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import org.appdevforall.cotg.flamegraph.internal.ancestryPath
import org.appdevforall.cotg.flamegraph.model.FlameNode
import org.appdevforall.cotg.flamegraph.ui.theme.Dimens

/**
 * The path from the true root down to the currently highlighted (selected) frame of [root]. Tapping
 * an ancestor crumb highlights it instead; the current (last) crumb is emphasized and inert. Pair
 * this above a [Flamegraph] sharing the same [state].
 */
@Composable
fun <T> FlamegraphBreadcrumbs(
    root: FlameNode<T>,
    state: FlamegraphState,
    modifier: Modifier = Modifier,
) {
    val path = remember(root, state.selectedKey) { ancestryPath(root, state.selectedKey) }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        path.forEachIndexed { index, ref ->
            val isCurrent = index == path.lastIndex
            if (index > 0) {
                Text(
                    text = "›",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = Dimens.crumbGap),
                )
            }
            val crumbModifier = if (isCurrent) {
                Modifier
            } else {
                Modifier.clickable { state.select(ref.key.ifEmpty { null }) }
            }
            Text(
                text = ref.node.label,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                modifier = crumbModifier.padding(horizontal = Dimens.crumbPaddingH, vertical = Dimens.crumbPaddingV),
            )
        }
    }
}

@Preview(name = "Breadcrumbs", widthDp = 360)
@Composable
private fun BreadcrumbsPreview() {
    val state = rememberFlamegraphState().apply { select("0/0") }
    PreviewScaffold { FlamegraphBreadcrumbs(root = SampleFlameData.cpu, state = state) }
}
