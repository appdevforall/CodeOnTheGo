package org.appdevforall.codeonthego.di

import org.appdevforall.codeonthego.app.IDEApplication
import org.appdevforall.codeonthego.repositories.PluginRepository
import org.appdevforall.codeonthego.repositories.PluginRepositoryImpl
import org.appdevforall.codeonthego.repositories.TemplateCollectionRepository
import org.appdevforall.codeonthego.repositories.TemplateCollectionRepositoryImpl
import org.appdevforall.codeonthego.viewmodels.ExternalFileInstallViewModel
import org.appdevforall.codeonthego.viewmodels.PluginManagerViewModel
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
