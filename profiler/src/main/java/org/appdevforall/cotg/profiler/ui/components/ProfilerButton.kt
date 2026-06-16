package org.appdevforall.cotg.profiler.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun ProfilerButton(
    modifier: Modifier = Modifier,
    label: String,
    labelStyle: TextStyle = TextStyle.Default,
    onClick: () -> Unit,
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
fun PreviewProfilerButton(){
    ProfilerButton(
        label = "My Btn",
    ) { }
}