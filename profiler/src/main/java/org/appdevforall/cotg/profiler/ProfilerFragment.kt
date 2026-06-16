package org.appdevforall.cotg.profiler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import org.appdevforall.cotg.profiler.ui.ProfilerScreenView
import org.appdevforall.cotg.profiler.ui.theme.ProfilerTheme
import org.appdevforall.cotg.profiler.ProfilerIntent.DumpHeap
import org.appdevforall.cotg.profiler.ProfilerIntent.CpuHotspot
class ProfilerFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ProfilerTheme {
                    ProfilerScreenView(onIntent = ::handleIntent)
                }
            }
        }

    private fun handleIntent(intent: ProfilerIntent) {
        when (intent) {
            DumpHeap -> Unit
            CpuHotspot -> Unit
        }
    }
}
