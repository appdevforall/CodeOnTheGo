package com.itsaky.androidide.di

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.data.repository.GeminiRepository
import com.itsaky.androidide.data.repository.GeminiRepositoryImpl
import com.itsaky.androidide.viewmodel.ChatViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Firebase.ai(backend = GenerativeBackend.vertexAI())
    }

    single<GeminiRepository> {
        GeminiRepositoryImpl(
            firebaseAI = get(),
            ideApi = IDEApiFacade,
        )
    }
    single { FileActionManager() }

    viewModel {
        ChatViewModel(geminiRepository = get())
    }
}
