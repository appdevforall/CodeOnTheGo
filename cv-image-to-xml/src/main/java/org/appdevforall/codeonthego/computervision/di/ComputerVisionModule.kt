package org.appdevforall.codeonthego.computervision.di

import org.appdevforall.codeonthego.computervision.data.repository.ComputerVisionRepository
import org.appdevforall.codeonthego.computervision.data.repository.ComputerVisionRepositoryImpl
import org.appdevforall.codeonthego.computervision.data.repository.DrawableImportHelper
import org.appdevforall.codeonthego.computervision.data.source.OcrSource
import org.appdevforall.codeonthego.computervision.data.source.YoloModelSource
import org.appdevforall.codeonthego.computervision.domain.RegionOcrProcessor
import org.appdevforall.codeonthego.computervision.ui.viewmodel.ComputerVisionViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val computerVisionModule = module {

    single { YoloModelSource() }

    single { OcrSource() }

    single { RegionOcrProcessor(ocrSource = get()) }

    single<ComputerVisionRepository> {
        ComputerVisionRepositoryImpl(
            assetManager = androidContext().assets,
            yoloModelSource = get(),
            regionOcrProcessor = get()
        )
    }

    single {
        DrawableImportHelper(
            contentResolver = androidContext().contentResolver
        )
    }

    viewModel { (layoutFilePath: String?, layoutFileName: String?) ->
        ComputerVisionViewModel(
            repository = get(),
            drawableImportHelper = get(),
            contentResolver = androidContext().contentResolver,
            layoutFilePath = layoutFilePath,
            layoutFileName = layoutFileName
        )
    }
}
