package io.github.rosemoe.sora.corpusharness;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import io.github.rosemoe.sora.text.ContentLine;

/**
 * Harness scaffolding for the quick-build corpus (ADFA-4128 D4) -- NOT part of upstream sora-editor. Exercises real editor-core content (ContentLine, LineSeparator) without pulling in CodeEditor's full transitive dependency graph (which this corpus tier deliberately excludes -- see README.md "sora-editor-lib" for why). Written in Java (not Kotlin) so it can reference the vendored Java classes directly -- the daemon's Kotlin-first compile pass has no visibility into co-located Java sources (see README).
 */
public class EditorHostActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ContentLine line = new ContentLine(SampleText.INITIAL);
		TextView view = new TextView(this);
		view.setText(line.toString());
		setContentView(view);
	}
}
