package com.itsaky.androidide.resources;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;

/**
 * A custom TextInputLayout that uses a GestureDetector to reliably detect
 * a long-press gesture anywhere within its bounds, without interfering
 * with child views like the TextInputEditText.
 */
public class TooltipTextInputLayout extends TextInputLayout implements ITooltipView {

    public interface OnLongPressListener {
        void onLongPress(View v);
    }

    private OnLongPressListener onLongPressListener;
    private final GestureDetector gestureDetector;

    private boolean isLongPressHandled;

    public TooltipTextInputLayout(@NonNull Context context) {
        this(context, null);
    }

    public TooltipTextInputLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TooltipTextInputLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                if (onLongPressListener != null) {
                    onLongPressListener.onLongPress(TooltipTextInputLayout.this);
                }
                isLongPressHandled = true;
            }
        });
    }

    @Override
    public void setTooltipLongPressListener(OnTooltipLongPressListener listener) {
        if (listener == null) {
            // Allow unsetting the listener
            setOnLongPressListener(null);
            return;
        }
        // Bridge our interface listener to the view's specific listener
        setOnLongPressListener(v -> listener.onLongPress());
    }

    public void setOnLongPressListener(@Nullable OnLongPressListener listener) {
        this.onLongPressListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // On the first touch event (ACTION_DOWN), reset the flag.
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            isLongPressHandled = false;
        }

        // Let the GestureDetector inspect the event. If it detects a long press,
        // it will set our 'isLongPressHandled' flag to true.
        gestureDetector.onTouchEvent(ev);

        // We intercept the touch event stream ONLY if our flag is true.
        // This "steals" the rest of the gesture (like the final ACTION_UP)
        // from child views. For a normal tap, this remains false.
        return isLongPressHandled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // This method is only called if onInterceptTouchEvent returns true.
        // We must return true here to signal that we have consumed the event.
        return true;
    }
}