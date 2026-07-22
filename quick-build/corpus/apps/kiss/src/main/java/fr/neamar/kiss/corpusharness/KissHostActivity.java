package fr.neamar.kiss.corpusharness;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import fr.neamar.kiss.normalizer.StringNormalizer;
import fr.neamar.kiss.utils.ClipboardUtils;

/**
 * Corpus harness entry point: exercises a real KISS subgraph (StringNormalizer,
 * ClipboardUtils) with no launcher/service/receiver/provider wiring.
 * See README.md.
 */
public class KissHostActivity extends Activity {

	static final int ID_SUMMARY = 6001;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		StringNormalizer.Result result = StringNormalizer.normalizeWithResult("Fromage-Ile", true);

		ClipboardUtils.setClipboard(this, "corpus harness clip");

		String summary = "normalized=" + result.toString() + " length=" + result.length();

		TextView view = new TextView(this);
		view.setId(ID_SUMMARY);
		view.setText(summary);
		setContentView(view);
	}
}
