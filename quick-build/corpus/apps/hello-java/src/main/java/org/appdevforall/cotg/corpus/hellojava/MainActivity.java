package org.appdevforall.cotg.corpus.hellojava;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

	public static final int ID_GREETING = 2001;

	private final Greeter greeter = new Greeter();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setGravity(Gravity.CENTER);

		TextView greetingView = new TextView(this);
		greetingView.setId(ID_GREETING);
		greetingView.setText(greeter.greet(getString(R.string.greeting_name)));
		root.addView(greetingView);

		Button detailButton = new Button(this);
		detailButton.setText(getString(R.string.detail_label));
		detailButton.setOnClickListener(v -> startActivity(new Intent(this, DetailActivity.class)));
		root.addView(detailButton);

		setContentView(root);
	}
}
