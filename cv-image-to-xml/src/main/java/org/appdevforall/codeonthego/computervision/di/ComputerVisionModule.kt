package org.appdevforall.codeonthego.computervision.di

import org.appdevforall.codeonthego.computervision.data.repository.DrawableImportHelper
import org.appdevforall.codeonthego.computervision.data.repository.VisionRepository
import org.appdevforall.codeonthego.computervision.data.repository.VisionRepositoryImpl
import org.appdevforall.codeonthego.computervision.data.source.OcrSource
import org.appdevforall.codeonthego.computervision.data.source.YoloModelSource
import org.appdevforall.codeonthego.computervision.domain.GenericBoxResolver
import org.appdevforall.codeonthego.computervision.domain.RegionOcrProcessor
import org.appdevforall.codeonthego.computervision.domain.usecase.GenerateXmlUC
import org.appdevforall.codeonthego.computervision.domain.usecase.ImportPlaceholderImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.PrepareImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.RemovePlaceholderImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.RunVisionUC
import org.appdevforall.codeonthego.computervision.ui.viewmodel.ComputerVisionViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val computerVisionModule = module {

    single { YoloModelSource() }
    single { OcrSource() }
    single { RegionOcrProcessor(ocrSource = get()) }
    single { GenericBoxResolver() }

    single<VisionRepository> {
        VisionRepositoryImpl(
            assetManager = androidContext().assets,
            yoloModelSource = get(),
            ocrSource = get()
        )
    }

    single { DrawableImportHelper(contentResolver = androidContext().contentResolver) }
    single { GenerateXmlUC() }
    single { ImportPlaceholderImageUC(drawableImportHelper = get()) }
    single { PrepareImageUC(contentResolver = androidContext().contentResolver) }
    single { RemovePlaceholderImageUC(drawableImportHelper = get()) }
    single { RunVisionUC(repository = get(), boxResolver = get(), regionOcrProcessor = get()) }

    viewModel { (layoutFilePath: String?, layoutFileName: String?) ->
        ComputerVisionViewModel(
            repository = get(),
            prepareImageUC = get(),
            runVisionUC = get(),
            generateXmlUC = get(),
            importPlaceholderImageUC = get(),
            removePlaceholderImageUC = get(),
            layoutFilePath = layoutFilePath,
            layoutFileName = layoutFileName
        )
    }
}
