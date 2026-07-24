package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Minimal translucent banner pinned just below the system status bar, as a child of the window DECOR with a status-bar top margin (the bar itself stays untouched; the app's own chrome may be overlapped - it is an error surface). ERROR-ONLY (plan A1): it renders build failures, payload crashes and the one-time gesture hint; success and in-progress states render nothing.
 *
 * Rendering is stateless: {@link #render} makes the banner match the given {@link OverlayState} exactly, creating/updating/removing as needed. There is no "clear" call to forget - every state change re-renders, which is what makes a stuck banner unrepresentable.
 */
final class StatusOverlay {

	/** View tag identifying the banner so re-renders update instead of stacking views. */
	private static final String VIEW_TAG = "com.itsaky.androidide.quickbuild.runtime.banner";

	private static final int COLOR_BUILD_FAILED = 0xCCBF360C;
	private static final int COLOR_CRASHED = 0xCCB71C1C;
	private static final int COLOR_HINT = 0xCC37474F;

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

	/** Must run on the main thread. Never throws: overlay failures are logged, not fatal. */
	void render(Activity activity, OverlayState state) {
		if (activity == null || state == null) {
			return;
		}
		try {
			// The DECOR, not android.R.id.content: under edge-to-edge, content starts
			// beneath the status bar, its root consumes the insets, and the decor's
			// action-bar container is a SIBLING of content that out-draws anything
			// inside it (elevation does not reorder across subtrees). A decor child
			// with a status-bar top margin is visible over the app's chrome while the
			// system status bar itself stays untouched.
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
	 * Start the banner just below the system status bar: a top MARGIN of the raw status-bar inset (getRootWindowInsets() - listener dispatch is consumed by the app's root and never reaches us). The deprecated accessor is the only one available at minSdk 28.
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

	/** Tap on a build failure jumps to the failing line in CoGo (plan A1). */
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
		// later-added sibling. Out-elevate them so the banner is never hidden behind the
		// app's own toolbar (system bars stay untouched - the inset handles those).
		banner.setElevation(16 * density);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.TOP);
		banner.setLayoutParams(params);
		return banner;
	}
}
