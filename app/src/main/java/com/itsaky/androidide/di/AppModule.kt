package com.itsaky.androidide.di

import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.agent.GeminiMacroProcessor
import com.itsaky.androidide.agent.repository.AgenticRunner
import com.itsaky.androidide.agent.repository.GeminiRepository
import com.itsaky.androidide.agent.repository.LocalLlmRepositoryImpl
import com.itsaky.androidide.agent.repository.SwitchableGeminiRepository
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.api.IDEApiFacade
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

// MODULE 1: Dependencies that are SAFE to create at startup
val coreModule = module {
    single { LocalLlmRepositoryImpl(context = androidContext(), ideApi = IDEApiFacade) }
    single { FileActionManager() }

    // Safely create the MacroProcessor with a null repository if the key is missing
    single { GeminiMacroProcessor(getOrNull()) }
}

// MODULE 2: Dependencies that require runtime configuration (like an API key)
val agentModule = module {
    // This factory will only be used by components that are created later (like the ChatFragment).
    // It will throw an error if the key is missing, which is what we want.
    factory<GeminiRepository> {
        SwitchableGeminiRepository(
            geminiRepository = AgenticRunner(context = androidContext()),
            localLlmRepository = get()
        )
    }

    // The ViewModel is tied to the agent, so it belongs in this module.
    viewModel {
        ChatViewModel()
    }
}