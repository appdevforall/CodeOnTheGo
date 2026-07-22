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
 * Persistent "back to CoGo" affordance (WS-G, complementing the 3-finger return gesture in {@link QuickBuildGestures}): a small semi-transparent circular button pinned to the screen's bottom-end corner, present on every activity of the test app for as long as the Quick Build runtime is installed. Exists because the gesture is undiscoverable on its own - the one-time text hint ({@link OverlayState#hint()}) fades after {@link QuickBuildRuntime#HINT_HIDE_MS} and never comes back, leaving a user who missed it with only the recents screen. Tapping runs the exact same {@link QuickBuildGestures#returnToIde} path the gesture already uses.
 *
 * Deliberately unobtrusive: small (see {@link #SIZE_DP}), low-alpha until touched, corner-anchored with a margin that also clears the system navigation bar (CLAUDE.md: never draw over the system bars) so it never sits over an app's own controls in the common case. It is its own decor-attached View, entirely outside the app's layout, so it never resizes or repositions anything the app under test draws.
 */
final class ReturnToIdeButton {

	private static final String VIEW_TAG = "com.itsaky.androidide.quickbuild.runtime.returnButton";

	/** ~60% opaque near-black - visible on any background without demanding attention. */
	private static final int BACKGROUND_COLOR = 0x992D3436;

	private static final int GLYPH_COLOR = Color.WHITE;
	private static final int SIZE_DP = 36;
	private static final int MARGIN_DP = 16;
	private static final float RESTING_ALPHA = 0.55f;

	/** Must run on the main thread. Never throws: a failure here must not affect the app under test. */
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
				// Already showing on this activity's current decor (e.g. a second resume
				// without an intervening recreate) - never stack a duplicate.
				return;
			}
			View button = createButton(activity);
			decor.addView(button);
			button.bringToFront();
		} catch (Throwable error) {
			RuntimeLog.w("return-to-ide button render failed", error);
		}
	}

	private View createButton(final Activity activity) {
		float density = activity.getResources().getDisplayMetrics().density;
		int size = (int) (SIZE_DP * density);
		int margin = (int) (MARGIN_DP * density);

		TextView button = new TextView(activity);
		button.setTag(VIEW_TAG);
		// No drawable resources in this AAR by design (it is injected into arbitrary
		// user projects) - a plain ASCII glyph, like StatusOverlay's banner is a plain
		// TextView.
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
	 * Extra bottom margin so the button clears the system navigation bar rather than sitting on top of it. The deprecated accessor is the only one available at minSdk 28 (mirrors {@code StatusOverlay#applyStatusBarInset}).
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
