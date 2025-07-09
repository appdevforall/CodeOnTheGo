package com.itsaky.androidide.editor.events

import io.github.rosemoe.sora.event.Event
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.widget.CodeEditor

class LanguageUpdateEvent(val language: Language?, editor: CodeEditor) : Event(editor)