package com.itsaky.androidide.di

import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.agent.GeminiMacroProcessor
import com.itsaky.androidide.agent.repository.LocalLlmRepositoryImpl
import com.itsaky.androidide.api.IDEApiFacade
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

// MODULE 1: Dependencies that are SAFE to create at startup
val coreModule = module {
    single { LocalLlmRepositoryImpl(context = androidContext(), ideApi = IDEApiFacade) }
    single { FileActionManager() }

    // Safely create the MacroProcessor with a null repository if the key is missing
    single { GeminiMacroProcessor(getOrNull()) }
}