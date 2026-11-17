package com.itsaky.androidide.di

import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.analytics.AnalyticsManager
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.deeplink.PendingDeepLinkOpen
import com.itsaky.androidide.editor.language.outline.OutlineProvider
import com.itsaky.androidide.editor.language.outline.TreeSitterOutlineProvider
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.plugins.manager.services.DebuggerEndpointSource
import com.itsaky.androidide.repositories.DebuggerEndpointRepository
import com.itsaky.androidide.repositories.RecentProjectRepository
import com.itsaky.androidide.repositories.RecentProjectRepositoryImpl
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.viewmodel.CloneRepositoryViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.OutlineViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

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

		single {
			get<RecentProjectRoomDatabase>().debuggerEndpointDao()
		}

		single {
			val packageManager = androidContext().packageManager
			DebuggerEndpointRepository(
				get(),
				get(named(APPLICATION_SCOPE)),
				isInstalled = { DebuggerEndpointRepository.isPackageInstalled(packageManager, it) },
			)
		}

		// What DebuggerEndpointService reads. Plugins call it off the main thread, so blocking on Room is fine.
		single<DebuggerEndpointSource> {
			val repository = get<DebuggerEndpointRepository>()
			DebuggerEndpointSource { location -> runBlocking { repository.reportedEndpoints(File(location)) } }
		}

		single<RecentProjectRepository> {
			RecentProjectRepositoryImpl(get())
		}

		single { GitCredentialsManager(get()) }

		single { PendingDeepLinkOpen() }
	}
