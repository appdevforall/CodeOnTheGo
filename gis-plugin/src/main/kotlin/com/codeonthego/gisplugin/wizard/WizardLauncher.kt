package com.codeonthego.gisplugin.wizard

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * Static handoff between a caller (a template recipe in production, an IDE
 * sidebar action during C1 manual testing) and the [WizardActivity] launched
 * by it.
 *
 * The classic "recipe-blocks-on-Activity" pattern (per ADFA-2435 §5e and
 * ADFA-2436 §5.2) needs a way for an Activity to return a result to a Kotlin
 * function blocked on a background thread. A static callback fits because:
 *
 *  - the recipe runs on a single background thread (set by
 *    `executeAsyncProvideError`); only one wizard at a time is in flight.
 *  - `Activity.startActivity()` from an Application context with
 *    `FLAG_ACTIVITY_NEW_TASK` is the canonical way to get a UI from a
 *    non-Activity context.
 *  - the Activity can call back via this object, which fulfils the
 *    `CompletableDeferred` the recipe is awaiting.
 *
 * Important: this approach is currently not wired up to the IDE's
 * `IdeTemplateService` because the CGT-registration path runs a fixed Pebble
 * recipe (see QUESTIONS.md). The launcher is shipped now so C2/C3/C4 have a
 * stable handoff once the API can call into plugin code.
 */
object WizardLauncher {

    @Volatile
    private var pending: CompletableDeferred<WizardResult?>? = null

    /**
     * Start the wizard from any context (including Application) and block
     * until the user finishes or cancels. Designed to be called from a
     * background thread — never call from the main thread (the wizard
     * Activity would deadlock waiting for itself to finish).
     *
     * @return null if the user cancelled, otherwise the wizard result.
     */
    fun launchAndAwait(context: Context): WizardResult? {
        check(pending == null) { "Another wizard is already in flight." }
        val deferred = CompletableDeferred<WizardResult?>()
        pending = deferred
        try {
            val intent = Intent(context, WizardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return runBlocking { deferred.await() }
        } finally {
            pending = null
        }
    }

    /** Called by [WizardActivity] when the wizard finishes or is cancelled. */
    internal fun complete(result: WizardResult?) {
        pending?.complete(result)
    }
}
