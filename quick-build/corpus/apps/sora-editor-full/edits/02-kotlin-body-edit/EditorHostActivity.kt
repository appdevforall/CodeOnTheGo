package org.appdevforall.corpusharness

import android.app.Activity
import android.os.Bundle
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Harness scaffolding for the quick-build corpus (ADFA-4128 WS-D) - NOT part of upstream
 * sora-editor.
 *
 * Deliberately KOTLIN referencing the Java [CodeEditor]: that is the exact direction the
 * corpus README's large-real-app finding 2 reported as impossible ("Unresolved reference
 * 'CodeEditor' in Kotlin sources"), so this file is the reproduction as much as it is an
 * entry point.
 */
class EditorHostActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val editor = CodeEditor(this)
		editor.setText("QB_SORA_KBODY_MARKER_V2")
		setContentView(editor)
	}
}
