package com.itsaky.androidide.resources;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.checkbox.MaterialCheckBox;

/**
 * A MaterialCheckBox that implements ITooltipView to provide a unified
 * long-press listener for tooltips.
 */
public class TooltipMaterialCheckBox extends MaterialCheckBox implements ITooltipView {

    public TooltipMaterialCheckBox(@NonNull Context context) {
        super(context);
    }

    public TooltipMaterialCheckBox(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TooltipMaterialCheckBox(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setTooltipLongPressListener(OnTooltipLongPressListener listener) {
        if (listener == null) {
            setOnLongClickListener(null);
            return;
        }
        // Bridge our interface listener to the standard Android OnLongClickListener
        setOnLongClickListener(v -> {
            listener.onLongPress();
            // Return true to consume the event, preventing other actions
            return true;
        });
    }
}