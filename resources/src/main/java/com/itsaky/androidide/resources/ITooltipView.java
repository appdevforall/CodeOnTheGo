package com.itsaky.androidide.resources;

/**
 * An interface for views that support a custom long-press action for showing tooltips.
 */
public interface ITooltipView {

    /**
     * A simple listener for long-press events.
     */
    interface OnTooltipLongPressListener {
        void onLongPress();
    }

    /**
     * Sets the listener to be called when a long-press gesture is detected.
     *
     * @param listener The listener to be invoked.
     */
    void setTooltipLongPressListener(OnTooltipLongPressListener listener);
}