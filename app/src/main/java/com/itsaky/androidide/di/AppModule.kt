package com.itsaky.androidide.di

//import com.itsaky.androidide.agent.GeminiMacroProcessor
import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.analytics.AnalyticsManager
import com.itsaky.androidide.analytics.IAnalyticsManager
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val coreModule = module {
    single { FileActionManager() }
//    single { GeminiMacroProcessor(get()) }

    // Analytics
    single<IAnalyticsManager> { AnalyticsManager(androidApplication()) }

    viewModel {
        ChatViewModel()
    }
}