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
import androidx.fragment.app.activityViewModels
import org.appdevforall.cotg.profiler.ui.ProfilerScreenView
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme

class ProfilerFragment : Fragment() {
    // Activity-scoped: the ViewModel (and the service connection it owns) survives this fragment's
    // view being destroyed/recreated when the bottom-sheet tab changes.
    private val viewModel: ProfilerViewModel by activityViewModels()

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

    // Bind only once this tab is actually visible. In the editor's ViewPager2 bottom sheet only the
    // visible page reaches onResume, so this avoids spawning the privileged process for pre-created
    // adjacent tabs. The connection lives in the (activity-scoped) ViewModel and is NOT torn down on
    // onStop, so an in-flight profiling session keeps running across tab switches; it is released
    // only when the ViewModel is cleared (editor activity destroyed).
    override fun onResume() {
        super.onResume()
        viewModel.connect()
    }
}
