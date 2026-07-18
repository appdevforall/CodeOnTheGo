package org.appdevforall.cotg.profiler.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun ProfilerButton(
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	labelStyle: TextStyle = TextStyle.Default,
) {
	Button(
		onClick = { onClick() },
		modifier = modifier,
	) {
		Text(text = label, style = labelStyle)
	}
}

@Preview
@Composable
private fun PreviewProfilerButton() {
	ProfilerButton(
		label = "My Btn",
		onClick = { },
	)
}
