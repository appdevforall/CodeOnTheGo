package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
	private static final int COLOR_NEUTRAL = 0xCC37474F;

	/**
	 * Decides what a status-bar inset read means.
	 *
	 * The null case is the one that matters. {@code getRootWindowInsets} returns null on the first render after a config-change recreate - a font-scale change, say - and a null read is not a zero inset: treating it as one leaves the top margin at 0 and draws the banner over the clock and the status icons. Wait for a real read instead.
	 *
	 * @param insetTop
	 *            the status-bar inset the window reported, or null when it has none yet
	 * @param bannerAttached
	 *            whether the banner is still in the view hierarchy; a detached banner ends the wait whatever the inset says
	 * @return what the caller should do with this read
	 */
	static InsetAction insetAction(Integer insetTop, boolean bannerAttached) {
		if (!bannerAttached) {
			return InsetAction.GIVE_UP;
		}
		return insetTop == null ? InsetAction.WAIT : InsetAction.APPLY;
	}

	/**
	 * Banner background color for a state kind.
	 *
	 * @param kind
	 *            the state being rendered; anything but the four failure kinds, HIDDEN included, takes the neutral color
	 * @return an ARGB color, deliberately translucent so the app stays readable behind it
	 */
	private static int colorFor(OverlayState.Kind kind) {
		switch (kind) {
		case BUILD_FAILED:
		case REINSTALL_PENDING:
			return COLOR_BUILD_FAILED;
		case CRASHED:
		case MIXED:
			return COLOR_CRASHED;
		default:
			return COLOR_NEUTRAL;
		}
	}

	/**
	 * Sets the banner's top margin, only when it changed, to avoid a needless relayout on every render.
	 *
	 * @param banner
	 *            the banner view whose layout params are updated in place
	 * @param top
	 *            the margin to set
	 */
	private static void setTopMargin(TextView banner, int top) {
		ViewGroup.LayoutParams lp = banner.getLayoutParams();
		if (lp instanceof FrameLayout.LayoutParams
				&& ((FrameLayout.LayoutParams) lp).topMargin != top) {
			((FrameLayout.LayoutParams) lp).topMargin = top;
			banner.setLayoutParams(lp);
		}
	}

	/**
	 * The window's status-bar inset.
	 *
	 * @param decor
	 *            the decor view to read the insets from
	 * @return the inset, or null when the window has no insets yet - explicitly not 0, which is a real inset value
	 */
	@SuppressWarnings("deprecation")
	private static Integer statusBarInsetTop(ViewGroup decor) {
		android.view.WindowInsets insets = decor.getRootWindowInsets();
		return insets == null ? null : Integer.valueOf(insets.getSystemWindowInsetTop());
	}

	/**
	 * Makes the banner on {@code activity} match {@code state}, adding, updating or removing it.
	 *
	 * Must run on the main thread. Never throws: overlay failures are logged, not fatal.
	 *
	 * @param activity
	 *            the activity whose decor view hosts the banner; null, or one without a window, is ignored
	 * @param state
	 *            the state to render; a HIDDEN state removes the banner, while null is ignored rather than treated as HIDDEN
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
			banner.bringToFront();
		} catch (Throwable error) {
			RuntimeLog.w("status overlay render failed", error);
		}
	}

	/**
	 * Sets the banner's top margin to the status-bar inset, so it starts just below the bar.
	 *
	 * Reads the inset directly because listener dispatch is consumed by the app's root and never reaches us. The deprecated accessor is the only one available at minSdk 28. When the read comes back null the margin is left alone and re-read after the next layout; see {@link #insetAction}.
	 *
	 * @param decor
	 *            the decor view the banner is attached to, the source of the insets
	 * @param banner
	 *            the banner view whose layout params are updated in place
	 */
	private void applyStatusBarInset(ViewGroup decor, TextView banner) {
		Integer top = statusBarInsetTop(decor);
		switch (insetAction(top, banner.getParent() != null)) {
		case APPLY:
			setTopMargin(banner, top);
			break;
		case WAIT:
			reapplyInsetAfterLayout(decor, banner);
			break;
		default:
			break;
		}
	}

	/**
	 * Builds the banner view; the caller sets its color and text.
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
		// The text is sp, so it grows with the system font scale: measured on an A56, the
		// banner wraps at about 53-58 characters per line at 1.0 and 25-32 at 2.0. The
		// budget belongs to CrashSummary, which sizes the message against this same
		// number - hold it in two places and the two drift.
		banner.setMaxLines(CrashSummary.MAX_BANNER_LINES);
		// Ellipsize so an overflow reads as one. Without this the text is cut mid-word and
		// looks like the whole message, which is how a truncated stack frame passes for a
		// complete one.
		banner.setEllipsize(TextUtils.TruncateAt.END);
		// Do NOT give this a movement method to make it scroll. The banner is a strip
		// sitting directly over the app's own toolbar - measured [0,0][1080,285] against
		// an appbar of [0,0][1080,180] - and setMovementMethod runs the framework's
		// focusable/clickable fixup, so the strip would start consuming touches meant for
		// the toolbar underneath: a truncation bug traded for a dead toolbar. Keeping the
		// message short enough not to need scrolling is CrashSummary's job instead.
		banner.setClickable(false);
		banner.setFocusable(false);
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

	/**
	 * Re-reads the inset after each layout until it is available, then applies it once.
	 *
	 * The listener removes itself on the first usable read, so two renders before one layout cost one extra no-op listener rather than a leak.
	 *
	 * @param decor
	 *            the decor view to re-read the insets from
	 * @param banner
	 *            the banner whose margin the deferred read updates
	 */
	private void reapplyInsetAfterLayout(final ViewGroup decor, final TextView banner) {
		final ViewTreeObserver observer = banner.getViewTreeObserver();
		observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
			@Override
			public void onGlobalLayout() {
				Integer top = statusBarInsetTop(decor);
				InsetAction action = insetAction(top, banner.getParent() != null);
				if (action == InsetAction.WAIT) {
					return;
				}
				// Captured at add time, not re-fetched: on the GIVE_UP branch the banner
				// is detached, and a detached view's getViewTreeObserver() returns a
				// fresh floating observer, so removing from it is a silent no-op and the
				// listener leaks with the decor. When the framework has already merged
				// the captured observer away (isAlive() false), the listener now lives
				// on the decor's observer, so remove there.
				if (observer.isAlive()) {
					observer.removeOnGlobalLayoutListener(this);
				} else {
					decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
				}
				if (action == InsetAction.APPLY) {
					setTopMargin(banner, top);
				}
			}
		});
	}

	/** What an inset read means: use it, wait for a real one, or stop waiting. */
	enum InsetAction {
		/** The inset is known: make it the banner's top margin and stop waiting. */
		APPLY,
		/** The window has no insets yet: leave the margin alone and read again after the next layout. */
		WAIT,
		/** The banner is no longer attached: stop waiting, so no listener outlives it. */
		GIVE_UP
	}
}
