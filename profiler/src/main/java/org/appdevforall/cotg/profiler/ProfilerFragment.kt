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
import org.appdevforall.cotg.profiler.ui.ProfilerScreenView
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

class ProfilerFragment : Fragment() {
    private val viewModel: ProfilerViewModel by viewModels()

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
                    ProfilerScreenView(state = state, onIntent = viewModel::onIntent)
                }
            }
        }
}
