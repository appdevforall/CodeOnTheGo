package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Draws the always-present "back to CoGo" button in the bottom-end corner of every proxy app activity.
 *
 * It exists because the 3-finger gesture in {@link QuickBuildGestures} is undiscoverable on its own: the one-time hint fades and never returns, leaving a user who missed it with only the recents screen. Tapping runs the same {@link QuickBuildGestures#returnToIde} path as the gesture.
 *
 * Kept unobtrusive: small, low-alpha, corner-anchored with a margin that clears the system navigation bar. It attaches to the decor view rather than the app's layout, so it never resizes or repositions anything the app under test draws.
 */
final class ReturnToIdeButton {

	/** View tag identifying the button so a re-render finds it instead of stacking a second one. */
	private static final String VIEW_TAG = "com.itsaky.androidide.quickbuild.runtime.returnButton";

	/** About 60% opaque near-black: visible on any background without demanding attention. */
	private static final int BACKGROUND_COLOR = 0x992D3436;

	private static final int GLYPH_COLOR = Color.WHITE;
	private static final int SIZE_DP = 36;
	private static final int MARGIN_DP = 16;
	private static final float RESTING_ALPHA = 0.55f;

	/**
	 * Adds the button to this activity's decor view, once.
	 *
	 * Must run on the main thread. Never throws: a failure here must not affect the app under test.
	 *
	 * @param activity
	 *            the activity whose decor view receives the button; null, or one without a window, is ignored. Calling again on the same decor is a no-op.
	 */
	void render(Activity activity) {
		if (activity == null) {
			return;
		}
		try {
			View decorView = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
			if (!(decorView instanceof ViewGroup)) {
				return;
			}
			ViewGroup decor = (ViewGroup) decorView;
			if (decor.findViewWithTag(VIEW_TAG) != null) {
				// Already on this decor, e.g. a second resume without a recreate in
				// between. Never stack a duplicate.
				return;
			}
			View button = createButton(activity);
			decor.addView(button);
			button.bringToFront();
		} catch (Throwable error) {
			RuntimeLog.w("return-to-ide button render failed", error);
		}
	}

	/**
	 * Builds the circular button view, sized and positioned for this activity's display.
	 *
	 * @param activity
	 *            supplies the display density for the dp sizes and is the click target's launch context; captured by the listener, so the view must not outlive it
	 * @return the tagged, laid-out button, not yet attached to anything
	 */
	private View createButton(final Activity activity) {
		float density = activity.getResources().getDisplayMetrics().density;
		int size = (int) (SIZE_DP * density);
		int margin = (int) (MARGIN_DP * density);

		TextView button = new TextView(activity);
		button.setTag(VIEW_TAG);
		// A plain ASCII glyph: this AAR ships no drawable resources, since it is
		// injected into arbitrary user projects.
		button.setText("<");
		button.setTextColor(GLYPH_COLOR);
		button.setTextSize(18f);
		button.setGravity(Gravity.CENTER);
		button.setAlpha(RESTING_ALPHA);
		GradientDrawable background = new GradientDrawable();
		background.setShape(GradientDrawable.OVAL);
		background.setColor(BACKGROUND_COLOR);
		button.setBackground(background);
		button.setElevation(16 * density);
		button.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View view) {
				QuickBuildGestures.returnToIde(activity);
			}
		});

		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
		int bottomMargin = margin + navigationBarInset(activity);
		params.setMargins(margin, margin, margin, bottomMargin);
		button.setLayoutParams(params);
		return button;
	}

	/**
	 * Height of the system navigation bar, added to the bottom margin so the button never sits on top of it.
	 *
	 * The deprecated accessor is the only one available at minSdk 28.
	 *
	 * @param activity
	 *            the activity whose window insets to read
	 * @return the bottom inset in pixels, 0 when the window or its insets are not available yet
	 */
	@SuppressWarnings("deprecation")
	private int navigationBarInset(Activity activity) {
		View decorView = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
		if (decorView == null) {
			return 0;
		}
		WindowInsets insets = decorView.getRootWindowInsets();
		return insets != null ? insets.getSystemWindowInsetBottom() : 0;
	}
}
