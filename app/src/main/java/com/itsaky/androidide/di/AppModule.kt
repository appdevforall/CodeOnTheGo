package com.itsaky.androidide.di


import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.agent.GeminiMacroProcessor
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.analytics.AnalyticsManager
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.viewmodel.CloneRepositoryViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import com.itsaky.androidide.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
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
            GitBottomSheetViewModel(get())
		}
        viewModel { MainViewModel(get()) }
        viewModel { CloneRepositoryViewModel(get(), get()) }


        single<CoroutineScope> {
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        single {
            RecentProjectRoomDatabase.getDatabase(androidApplication(), get())
        }

        single {
            get<RecentProjectRoomDatabase>().recentProjectDao()
        }

        single { GitCredentialsManager(get()) }

	}
