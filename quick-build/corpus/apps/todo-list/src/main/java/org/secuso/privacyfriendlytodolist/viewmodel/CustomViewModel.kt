package org.secuso.privacyfriendlytodolist.viewmodel

import android.content.Context
import org.secuso.privacyfriendlytodolist.model.ModelServices
import org.secuso.privacyfriendlytodolist.model.StubModelServices

// Scaffolding, not vendored: the real CustomViewModel builds ModelServices via Model.createServices()
// (coroutines + Room). This stand-in gives service/ModelJobBase.kt (real, vendored) the same
// `val model: ModelServices` surface without the DB/coroutine machinery this subgraph excludes.
class CustomViewModel(context: Context) {
    val model: ModelServices = StubModelServices()

    fun destroy() {}
}
