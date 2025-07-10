package com.itsaky.androidide.editor.events

import io.github.rosemoe.sora.event.Event
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

class FileUpdateEvent(val file: File?, editor: CodeEditor): Event(editor)