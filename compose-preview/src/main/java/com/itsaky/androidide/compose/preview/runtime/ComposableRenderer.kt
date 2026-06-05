package com.itsaky.androidide.compose.preview.runtime

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

class ComposableRenderer(
    private val composeView: ComposeView
) {

    private var watchdog: Runnable? = null

    fun render(
        clazz: Class<*>,
        functionName: String,
        resourceContext: Context?,
        parameterValue: Any? = null,
        parameterIndex: Int = 0
    ) {
        cancelWatchdog()

        val composableMethod = ComposableInvoker.findComposableMethod(clazz, functionName)
        if (composableMethod == null) {
            showError("Composable function not found: $functionName")
            return
        }

        startWatchdog(functionName)

        try {
            composeView.setContent {
                val previewContext = resourceContext ?: LocalContext.current
                val previewConfiguration = previewContext.resources.configuration
                val previewDensity = Density(
                    previewContext.resources.displayMetrics.density,
                    previewConfiguration.fontScale
                )
                CompositionLocalProvider(
                    LocalContext provides previewContext,
                    LocalConfiguration provides previewConfiguration,
                    LocalDensity provides previewDensity
                ) {
                    MaterialTheme {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            val setupError = remember { mutableStateOf<String?>(null) }
                            val message = setupError.value
                            if (message != null) {
                                ErrorContent(message)
                            } else {
                                RenderComposable(clazz, composableMethod, parameterValue, parameterIndex) { cause ->
                                    LOG.error("render: setup failed fn={} - {}", functionName, describe(cause), cause)
                                    setupError.value = describe(cause)
                                }
                            }
                            SideEffect { cancelWatchdog() }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            cancelWatchdog()
            val cause = (e as? PreviewSetupException)?.cause ?: e.cause ?: e
            LOG.error("render: FAILED fn={} - {}", functionName, describe(cause), e)
            showError(describe(cause))
        }
    }

    @Composable
    private fun RenderComposable(
        clazz: Class<*>,
        method: Method,
        parameterValue: Any?,
        parameterIndex: Int,
        onSetupError: (Throwable) -> Unit
    ) {
        val composer = currentComposer
        try {
            ComposableInvoker.invokeSafely(clazz, method, composer, parameterValue, parameterIndex)
        } catch (e: PreviewSetupException) {
            onSetupError(e)
        }
    }

    private fun startWatchdog(functionName: String) {
        val runnable = Runnable {
            watchdog = null
            if (!composeView.isAttachedToWindow) {
                return@Runnable
            }
            LOG.warn("Preview render timed out for {}", functionName)
            showError(
                "Preview timed out after $RENDER_TIMEOUT_MS ms.\n" +
                    "Possible infinite loop or runaway recomposition in @$functionName."
            )
        }
        watchdog = runnable
        composeView.postDelayed(runnable, RENDER_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { composeView.removeCallbacks(it) }
        watchdog = null
    }

    private fun showError(message: String) {
        cancelWatchdog()
        composeView.disposeComposition()
        composeView.setContent {
            MaterialTheme {
                ErrorContent(message)
            }
        }
    }

    @Composable
    private fun ErrorContent(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFFF3F3))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Preview Error",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFB00020)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    private fun describe(throwable: Throwable): String {
        val type = throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name }
        return "$type: ${throwable.message ?: "no message"}"
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposableRenderer::class.java)
        private const val RENDER_TIMEOUT_MS = 10_000L
    }
}
