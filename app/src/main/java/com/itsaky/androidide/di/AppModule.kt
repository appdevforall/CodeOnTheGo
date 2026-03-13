package com.itsaky.androidide.di

import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.agent.GeminiMacroProcessor
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.analytics.AnalyticsManager
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel

val coreModule =
	module {
		single { FileActionManager() }
		single { GeminiMacroProcessor(get()) }

		// Analytics
		single<IAnalyticsManager> { AnalyticsManager() }

		viewModel {
			ChatViewModel()
		}
		viewModel {
            GitBottomSheetViewModel()
		}
	}
