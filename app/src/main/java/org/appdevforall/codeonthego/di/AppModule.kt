package org.appdevforall.codeonthego.di

import org.appdevforall.codeonthego.actions.FileActionManager
import org.appdevforall.codeonthego.analytics.AnalyticsManager
import org.appdevforall.codeonthego.analytics.IAnalyticsManager
import org.appdevforall.codeonthego.deeplink.PendingDeepLinkOpen
import org.appdevforall.codeonthego.editor.language.outline.OutlineProvider
import org.appdevforall.codeonthego.editor.language.outline.TreeSitterOutlineProvider
import org.appdevforall.codeonthego.git.core.GitCredentialsManager
import org.appdevforall.codeonthego.repositories.RecentProjectRepository
import org.appdevforall.codeonthego.repositories.RecentProjectRepositoryImpl
import org.appdevforall.codeonthego.roomData.recentproject.RecentProjectRoomDatabase
import org.appdevforall.codeonthego.viewmodel.CloneRepositoryViewModel
import org.appdevforall.codeonthego.viewmodel.GitBottomSheetViewModel
import org.appdevforall.codeonthego.viewmodel.MainViewModel
import org.appdevforall.codeonthego.viewmodel.OutlineViewModel
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
		single<OutlineProvider> { TreeSitterOutlineProvider(androidContext()) }
		viewModel { OutlineViewModel(get()) }

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
