package com.itsaky.androidide.di

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.data.repository.GeminiRepository
import com.itsaky.androidide.data.repository.GeminiRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val appModule = module {
    // Provides a single, shared instance of the Ktor HttpClient
    single {
        HttpClient(Android) {
            // Install the ContentNegotiation plugin for JSON serialization
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true // Important for robust parsing
                })
            }
        }
    }

    single {
        Firebase.ai(backend = GenerativeBackend.googleAI())
    }

    single<GeminiRepository> {
        GeminiRepositoryImpl(
            firebaseAI = get(),
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

}
