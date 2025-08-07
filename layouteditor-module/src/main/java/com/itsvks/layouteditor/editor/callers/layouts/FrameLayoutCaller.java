package com.itsvks.layouteditor.editor.callers.layouts;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import com.itsvks.layouteditor.utils.Constants;

public class FrameLayoutCaller {
  public static void setForegroundGravity(View target, String value, Context context) {
    String[] flags = value.split("\\|");
    int result = 0;

    for (String flag : flags) {
      result |= Constants.gravityMap.get(flag);
    }

    target.setForegroundGravity(result);
    target.requestLayout();
  }

  public static void setMeasureAllChildren(View target, String value, Context context) {
    ((FrameLayout) target).setMeasureAllChildren(Boolean.parseBoolean(value));
  }
}