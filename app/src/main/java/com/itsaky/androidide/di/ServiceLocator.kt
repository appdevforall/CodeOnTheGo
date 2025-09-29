package com.itsaky.androidide.di

import com.itsaky.androidide.agent.repository.GeminiRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A simple Service Locator to lazily access Koin-managed dependencies.
 * This is useful for components that need to be initialized *after* Koin has started
 * but *before* a specific screen is shown.
 */
object ServiceLocator : KoinComponent {
    // This will fetch the GeminiRepository only when it's first accessed.
    // If it fails (due to a missing API key), the exception will be thrown here.
    val geminiRepository: GeminiRepository by inject()
}