package org.appdevforall.cotg.profiler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import org.appdevforall.cotg.profiler.service.ProfilerServiceConnection
import org.appdevforall.cotg.profiler.ui.ProfilerScreenView
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

class ProfilerFragment : Fragment() {
    private val viewModel: ProfilerViewModel by viewModels()

    private val connection: ProfilerServiceConnection by lazy {
        ProfilerServiceConnection(
            context = requireContext().applicationContext,
            onConnected = viewModel::onServiceConnected,
            onDisconnected = viewModel::onServiceDisconnected,
            onUnavailable = viewModel::onServiceUnavailable,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.state.collectAsState()
                ProfilerTheme {
                    ProfilerScreenView(state = state, onIntent = {
                        connection.connect()
                        viewModel.onIntent(it)
                    })
                }
            }
        }

    // Bind only once this tab is actually visible (resumed). In the editor's ViewPager2 bottom
    // sheet, offscreen tabs are only STARTED, so this avoids spawning the privileged process for a
    // pre-created adjacent tab that the user never opens.
    override fun onResume() {
        super.onResume()
        connection.connect()
    }

    override fun onStop() {
        connection.disconnect()
        super.onStop()
    }
}
