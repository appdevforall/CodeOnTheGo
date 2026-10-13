package org.appdevforall.codeonthego.di

import org.appdevforall.codeonthego.repositories.TemplateRepository
import org.appdevforall.codeonthego.repositories.TemplateRepositoryImpl
import org.appdevforall.codeonthego.utils.Environment
import org.appdevforall.codeonthego.viewmodels.TemplateManagerViewModel
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
