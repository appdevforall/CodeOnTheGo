package appdevforall.codeonthego.computervision.di
import appdevforall.codeonthego.computervision.data.repository.ComputerVisionRepository
import appdevforall.codeonthego.computervision.data.repository.ComputerVisionRepositoryImpl
import appdevforall.codeonthego.computervision.data.source.OcrSource
import appdevforall.codeonthego.computervision.data.source.YoloModelSource
import appdevforall.codeonthego.computervision.presentation.ui.viewmodel.ComputerVisionViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val computerVisionModule = module {

    single { YoloModelSource() }

    single { OcrSource() }

    single<ComputerVisionRepository> {
        ComputerVisionRepositoryImpl(
            assetManager = androidContext().assets,
            yoloModelSource = get(),
            ocrSource = get()
        )
    }

    viewModel { (layoutFilePath: String?, layoutFileName: String?) ->
        ComputerVisionViewModel(
            repository = get(),
            contentResolver = androidContext().contentResolver,
            layoutFilePath = layoutFilePath,
            layoutFileName = layoutFileName
        )
    }
}
