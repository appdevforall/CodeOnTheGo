package com.itsaky.androidide.di

import com.itsaky.androidide.repositories.TemplateRepository
import com.itsaky.androidide.repositories.TemplateRepositoryImpl
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.viewmodels.TemplateManagerViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
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
			)
		}
	}
