package com.itsaky.androidide.di

import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.agent.GeminiMacroProcessor
import com.itsaky.androidide.agent.repository.GeminiRepository
import com.itsaky.androidide.agent.repository.GeminiRepositoryImpl
import com.itsaky.androidide.agent.repository.LocalLlmRepositoryImpl
import com.itsaky.androidide.agent.repository.SwitchableGeminiRepository
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.api.IDEApiFacade
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { GeminiRepositoryImpl(ideApi = IDEApiFacade, context = androidContext()) }
    single { LocalLlmRepositoryImpl(context = androidContext(), ideApi = IDEApiFacade) }
    single<GeminiRepository> {
        SwitchableGeminiRepository(
            geminiRepository = get(),
            localLlmRepository = get()
        )
    }
    single { FileActionManager() }
    single { GeminiMacroProcessor(get()) }

    viewModel {
        ChatViewModel(agentRepository = get())
    }
}
