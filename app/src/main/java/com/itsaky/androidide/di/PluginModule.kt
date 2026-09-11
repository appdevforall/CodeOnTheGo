package com.itsaky.androidide.di

import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.repositories.PluginRepositoryImpl
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import com.itsaky.androidide.repositories.TemplateCollectionRepositoryImpl
import com.itsaky.androidide.viewmodels.ExternalFileInstallViewModel
import com.itsaky.androidide.viewmodels.PluginManagerViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * Koin module for plugin-related dependencies
 */
val pluginModule =
	module {

		// Repository
		single<PluginRepository> {
			PluginRepositoryImpl(
				pluginManagerProvider = { IDEApplication.getPluginManager() },
				pluginsDir = File(IDEApplication.cachedFilesDir, "plugins"),
			)
		}

		single<TemplateCollectionRepository> {
			TemplateCollectionRepositoryImpl()
		}

		// ViewModel
		viewModel {
			PluginManagerViewModel(
				pluginRepository = get(),
				contentResolver = androidContext().contentResolver,
				filesDir = IDEApplication.cachedFilesDir,
			)
		}

		viewModel {
			ExternalFileInstallViewModel(
				pluginRepository = get(),
				templateCollectionRepository = get(),
				contentResolver = androidContext().contentResolver,
				filesDir = IDEApplication.cachedFilesDir,
			)
		}
	}
