package de.danoeh.antennapod.corpusharness;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.util.ArrayList;

import de.danoeh.antennapod.model.feed.FeedFunding;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.playback.MediaType;

/**
 * Corpus harness entry point: exercises a real AntennaPod {@code :model} subgraph
 * (FeedItemFilter, MediaType, FeedFunding) with no Room/database/UI framework wiring.
 * See README.md.
 */
public class AntennaPodHostActivity extends Activity {

	static final int ID_SUMMARY = 4001;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		FeedItemFilter filter = new FeedItemFilter(
				new String[] { FeedItemFilter.DOWNLOADED, FeedItemFilter.QUEUED });
		MediaType mediaType = MediaType.fromMimeType("audio/mpeg");

		ArrayList<FeedFunding> funding = FeedFunding.extractPaymentLinks(
				"https://example.com/donate" + FeedFunding.FUNDING_TITLE_SEPARATOR + "Support the show");
		String fundingBack = FeedFunding.getPaymentLinksAsString(funding);

		String summary = "showDownloaded=" + filter.isShowDownloaded()
				+ " mediaType=" + mediaType
				+ " fundingRoundTrip=" + fundingBack;

		TextView view = new TextView(this);
		view.setId(ID_SUMMARY);
		view.setText(summary);
		setContentView(view);
	}
}
