package com.itsaky.androidide.di

import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.assets.addons.BundledAddonsRecord
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import com.itsaky.androidide.repositories.TemplateRepository
import com.itsaky.androidide.repositories.TemplateRepositoryImpl
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.viewmodels.TemplateManagerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for template-related dependencies
 */
val templateModule =
	module {

		// Repository
		single<TemplateRepository> {
			TemplateRepositoryImpl(
				templatesDir = Environment.TEMPLATES_DIR,
				downloadDir = Environment.DOWNLOAD_DIR,
			)
		}

		// ViewModel
		viewModel {
			TemplateManagerViewModel(
				templateRepository = get(),
				collectionInstalls = get<TemplateCollectionRepository>().installs,
				isBundledTemplate = { fileName ->
					BundledAddonsRecord
						.inFilesDir(IDEApplication.cachedFilesDir)
						.read()
						?.templateFiles
						?.contains(fileName) == true
				},
			)
		}
	}
