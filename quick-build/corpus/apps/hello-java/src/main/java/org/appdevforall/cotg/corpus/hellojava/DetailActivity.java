package org.appdevforall.cotg.corpus.hellojava;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class DetailActivity extends Activity {

	public static final int ID_DETAIL = 2002;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		TextView view = new TextView(this);
		view.setId(ID_DETAIL);
		view.setText("Detail screen: " + new Greeter().greet("Detail visitor"));
		setContentView(view);
	}
}
