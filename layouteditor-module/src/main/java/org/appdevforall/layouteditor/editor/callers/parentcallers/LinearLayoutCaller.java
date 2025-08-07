package com.itsvks.layouteditor.editor.callers.parentcallers;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.itsvks.layouteditor.utils.DimensionUtil;

public class LinearLayoutCaller {
  public static void setLayoutWeight(View target, String value, Context context) {
    ((LinearLayout.LayoutParams) target.getLayoutParams()).weight = Float.parseFloat(value);
    target.requestLayout();
  }

  public static void setLayoutMargin(View target, String value, Context context) {
    int margin = (int) DimensionUtil.parse(value, context);
    ((LinearLayout.LayoutParams) target.getLayoutParams()).setMargins(margin, margin, margin, margin);
    target.requestLayout();
  }

  public static void setLayoutMarginLeft(View target, String value, Context context) {
    ((LinearLayout.LayoutParams) target.getLayoutParams()).leftMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }

  public static void setLayoutMarginRight(View target, String value, Context context) {
    ((LinearLayout.LayoutParams) target.getLayoutParams()).rightMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }

  public static void setLayoutMarginTop(View target, String value, Context context) {
    ((LinearLayout.LayoutParams) target.getLayoutParams()).topMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }

  public static void setLayoutMarginBottom(View target, String value, Context context) {
    ((LinearLayout.LayoutParams) target.getLayoutParams()).bottomMargin = (int) DimensionUtil.parse(value, context);
    target.requestLayout();
  }
}
