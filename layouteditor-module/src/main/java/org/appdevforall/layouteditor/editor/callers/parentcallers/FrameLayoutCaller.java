package com.itsvks.layouteditor.editor.callers.parentcallers;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import com.itsvks.layouteditor.utils.Constants;
import com.itsvks.layouteditor.utils.DimensionUtil;

public class FrameLayoutCaller {
  public static void setLayoutGravity(View target, String value, Context context) {
    String[] flags = value.split("\\|");
    int result = 0;

    for (String flag : flags) {
      result |= Constants.gravityMap.get(flag);
    }

    ((FrameLayout.LayoutParams) target.getLayoutParams()).gravity = result;
    target.requestLayout();
  }

  public static void setLayoutMargin(View target, String value, Context context) {
    int margin = (int) DimensionUtil.parse(value, context);
    ((FrameLayout.LayoutParams) target.getLayoutParams()).setMargins(margin, margin, margin, margin);
    target.requestLayout();
  }

  public static void setLayoutMarginLeft(View target, String value, Context context) {
    ((FrameLayout.LayoutParams) target.getLayoutParams()).leftMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }

  public static void setLayoutMarginRight(View target, String value, Context context) {
    ((FrameLayout.LayoutParams) target.getLayoutParams()).rightMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }

  public static void setLayoutMarginTop(View target, String value, Context context) {
    ((FrameLayout.LayoutParams) target.getLayoutParams()).topMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }

  public static void setLayoutMarginBottom(View target, String value, Context context) {
    ((FrameLayout.LayoutParams) target.getLayoutParams()).bottomMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }
}
