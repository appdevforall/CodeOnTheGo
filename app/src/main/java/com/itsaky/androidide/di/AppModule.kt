package com.itsaky.androidide.di

import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.analytics.AnalyticsManager
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.deeplink.PendingDeepLinkOpen
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.repositories.RecentProjectRepository
import com.itsaky.androidide.repositories.RecentProjectRepositoryImpl
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.viewmodel.CloneRepositoryViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import com.itsaky.androidide.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the process-lifetime [CoroutineScope]; see the binding for why it is named. */
const val APPLICATION_SCOPE = "applicationScope"

val coreModule =
	module {
		single { FileActionManager() }

		// Analytics
		single<IAnalyticsManager> { AnalyticsManager() }

		viewModel {
			GitBottomSheetViewModel(get())
		}
		viewModel { MainViewModel() }
		viewModel { CloneRepositoryViewModel(get(), get()) }

		// Named, because an unqualified single<CoroutineScope> is claimed by type alone: this one
		// instance was serving both the Room database below and EditorHandlerActivity's saveAllAsync,
		// and a second unqualified CoroutineScope added anywhere would silently retarget the save with
		// no compile error and no failing test. Consumers now ask for it by name.
		single<CoroutineScope>(named(APPLICATION_SCOPE)) {
			CoroutineScope(SupervisorJob() + Dispatchers.IO)
		}

		single {
			RecentProjectRoomDatabase.getDatabase(androidApplication(), get(named(APPLICATION_SCOPE)))
		}

		single {
			get<RecentProjectRoomDatabase>().recentProjectDao()
		}

		single<RecentProjectRepository> {
			RecentProjectRepositoryImpl(get())
		}

		single { GitCredentialsManager(get()) }

		single { PendingDeepLinkOpen() }
	}
