package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Draws the translucent status banner just below the system status bar.
 *
 * It attaches to the window decor with a status-bar top margin, so the system bar stays untouched while the app's own chrome may be overlapped; this is an error surface. Rendering is stateless: {@link #render} makes the banner match the given {@link OverlayState} exactly, creating, updating or removing it. There is no separate clear call to forget, which is what makes a stuck banner impossible.
 */
final class StatusOverlay {

	/** View tag identifying the banner so re-renders update instead of stacking views. */
	private static final String VIEW_TAG = "com.itsaky.androidide.quickbuild.runtime.banner";

	private static final int COLOR_BUILD_FAILED = 0xCCBF360C;
	private static final int COLOR_CRASHED = 0xCCB71C1C;
	private static final int COLOR_HINT = 0xCC37474F;

	/**
	 * Banner background color for a state kind.
	 *
	 * @param kind
	 *            the state being rendered; anything but BUILD_FAILED and CRASHED, HIDDEN included, takes the neutral hint color
	 * @return an ARGB color, deliberately translucent so the app stays readable behind it
	 */
	private static int colorFor(OverlayState.Kind kind) {
		switch (kind) {
		case BUILD_FAILED:
			return COLOR_BUILD_FAILED;
		case CRASHED:
			return COLOR_CRASHED;
		default:
			return COLOR_HINT;
		}
	}

	/**
	 * Makes the banner on {@code activity} match {@code state}, adding, updating or removing it.
	 *
	 * Must run on the main thread. Never throws: overlay failures are logged, not fatal.
	 *
	 * @param activity
	 *            the activity whose decor view hosts the banner; null, or one without a window, is ignored
	 * @param state
	 *            the state to render; a HIDDEN state removes the banner, so this is the only call needed to clear it. Null is ignored rather than treated as HIDDEN.
	 */
	void render(Activity activity, OverlayState state) {
		if (activity == null || state == null) {
			return;
		}
		try {
			// The decor, not android.R.id.content: under edge-to-edge the content root
			// consumes the insets, and the decor's action-bar container is a sibling
			// that out-draws anything inside content, since elevation does not reorder
			// across subtrees.
			View decorView = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
			if (!(decorView instanceof ViewGroup)) {
				return;
			}
			ViewGroup decor = (ViewGroup) decorView;
			TextView banner = decor.findViewWithTag(VIEW_TAG);
			if (state.kind == OverlayState.Kind.HIDDEN) {
				if (banner != null) {
					decor.removeView(banner);
				}
				return;
			}
			if (banner == null) {
				banner = createBanner(activity);
				decor.addView(banner);
			}
			applyStatusBarInset(decor, banner);
			banner.setBackgroundColor(colorFor(state.kind));
			banner.setText(state.text());
			bindJump(banner, activity, state);
			banner.bringToFront();
		} catch (Throwable error) {
			RuntimeLog.w("status overlay render failed", error);
		}
	}

	/**
	 * Sets the banner's top margin to the status-bar inset, so it starts just below the bar.
	 *
	 * Reads the inset directly because listener dispatch is consumed by the app's root and never reaches us. The deprecated accessor is the only one available at minSdk 28.
	 *
	 * @param decor
	 *            the decor view the banner is attached to, the source of the insets
	 * @param banner
	 *            the banner view whose layout params are updated in place, and only when the margin actually changed, to avoid a needless relayout on every render
	 */
	@SuppressWarnings("deprecation")
	private void applyStatusBarInset(ViewGroup decor, TextView banner) {
		android.view.WindowInsets insets = decor.getRootWindowInsets();
		int top = insets != null ? insets.getSystemWindowInsetTop() : 0;
		ViewGroup.LayoutParams lp = banner.getLayoutParams();
		if (lp instanceof FrameLayout.LayoutParams
				&& ((FrameLayout.LayoutParams) lp).topMargin != top) {
			((FrameLayout.LayoutParams) lp).topMargin = top;
			banner.setLayoutParams(lp);
		}
	}

	/**
	 * Makes the banner tappable when the state has an error location, and inert otherwise.
	 *
	 * @param banner
	 *            the banner view whose click listener is set or cleared
	 * @param activity
	 *            the source of the application context the jump intent is started from
	 * @param state
	 *            supplies the file and position to jump to; re-bound on every render, so a stale listener can never point at the previous error
	 */
	private void bindJump(TextView banner, final Activity activity, final OverlayState state) {
		if (state.canJumpToEditor()) {
			banner.setOnClickListener(new View.OnClickListener() {

				@Override
				public void onClick(View view) {
					JumpToEditor.open(activity.getApplicationContext(), state.file, state.line,
							state.column);
				}
			});
		} else {
			banner.setOnClickListener(null);
			banner.setClickable(false);
		}
	}

	/**
	 * Builds the banner view; the caller sets its color, text and click target.
	 *
	 * @param activity
	 *            supplies the display density for the padding and elevation
	 * @return the tagged, full-width, top-anchored banner, not yet attached to anything
	 */
	private TextView createBanner(Activity activity) {
		TextView banner = new TextView(activity);
		banner.setTag(VIEW_TAG);
		banner.setTextColor(Color.WHITE);
		banner.setTextSize(12f);
		banner.setMaxLines(6);
		float density = activity.getResources().getDisplayMetrics().density;
		final int padding = (int) (8 * density);
		banner.setPadding(padding, padding, padding, padding);
		// Sibling order is not enough: app bars carry elevation and draw above a plain
		// later-added sibling, so out-elevate them.
		banner.setElevation(16 * density);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.TOP);
		banner.setLayoutParams(params);
		return banner;
	}
}
