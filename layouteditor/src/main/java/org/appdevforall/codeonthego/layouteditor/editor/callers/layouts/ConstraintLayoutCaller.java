package org.appdevforall.codeonthego.layouteditor.editor.callers.layouts;

import android.content.Context;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import org.appdevforall.codeonthego.layouteditor.managers.IdManager;
import org.appdevforall.codeonthego.layouteditor.utils.DimensionUtil;

public class ConstraintLayoutCaller {

  private static final int PARENT_ID = ConstraintSet.PARENT_ID;
  private static final int LEFT = ConstraintSet.LEFT;
  private static final int RIGHT = ConstraintSet.RIGHT;
  private static final int TOP = ConstraintSet.TOP;
  private static final int BOTTOM = ConstraintSet.BOTTOM;
  private static final int BASELINE = ConstraintSet.BASELINE;
  private static final int START = ConstraintSet.START;
  private static final int END = ConstraintSet.END;

  private static ConstraintSet cloneWithIds(ConstraintLayout layout) {
    for (int i = 0; i < layout.getChildCount(); i++) {
      View child = layout.getChildAt(i);
      if (child.getId() == View.NO_ID) {
        child.setId(View.generateViewId());
      }
    }
    ConstraintSet set = new ConstraintSet();
    set.clone(layout);
    return set;
  }

  private static ConstraintLayout getParentLayout(View target) {
    return (ConstraintLayout) target.getParent();
  }

  private static void setConstraint(
      ConstraintLayout layout, View target, String value, int startSide, int endSide) {
    ConstraintSet set = cloneWithIds(layout);
    int targetViewId = "parent".equals(value) ? PARENT_ID : IdManager.getViewId(value);
    set.connect(target.getId(), startSide, targetViewId, endSide);
    set.applyTo(layout);
  }

  private static void setConstraint(View target, String value, int startSide, int endSide) {
    setConstraint(getParentLayout(target), target, value, startSide, endSide);
  }

  private static void setMargin(ConstraintLayout layout, View target, int side, int value) {
    ConstraintSet set = cloneWithIds(layout);
    set.setMargin(target.getId(), side, value);
    set.applyTo(layout);
  }

  private static void setMargin(View target, int side, String value) {
    ConstraintLayout layout = getParentLayout(target);
    int margin = (int) DimensionUtil.parse(value, target.getContext());
    setMargin(layout, target, side, margin);
  }

  private static void setGoneMargin(ConstraintLayout layout, View target, int side, int value) {
    ConstraintSet set = cloneWithIds(layout);
    set.setGoneMargin(target.getId(), side, value);
    set.applyTo(layout);
  }

  private static void setGoneMargin(View target, int side, String value) {
    ConstraintLayout layout = getParentLayout(target);
    int margin = (int) DimensionUtil.parse(value, target.getContext());
    setGoneMargin(layout, target, side, margin);
  }

  public static void setLeftToLeft(View target, String value, Context context) {
    setConstraint(target, value, LEFT, LEFT);
  }

  public static void setLeftToRight(View target, String value, Context context) {
    setConstraint(target, value, LEFT, RIGHT);
  }

  public static void setRightToLeft(View target, String value, Context context) {
    setConstraint(target, value, RIGHT, LEFT);
  }

  public static void setRightToRight(View target, String value, Context context) {
    setConstraint(target, value, RIGHT, RIGHT);
  }

  public static void setTopToTop(View target, String value, Context context) {
    setConstraint(target, value, TOP, TOP);
  }

  public static void setTopToBottom(View target, String value, Context context) {
    setConstraint(target, value, TOP, BOTTOM);
  }

  public static void setBottomToTop(View target, String value, Context context) {
    setConstraint(target, value, BOTTOM, TOP);
  }

  public static void setBottomToBottom(View target, String value, Context context) {
    setConstraint(target, value, BOTTOM, BOTTOM);
  }

  public static void setBaselineToBaseline(View target, String value, Context context) {
    setConstraint(target, value, BASELINE, BASELINE);
  }

  public static void setStartToStart(View target, String value, Context context) {
    setConstraint(target, value, START, START);
  }

  public static void setStartToEnd(View target, String value, Context context) {
    setConstraint(target, value, START, END);
  }

  public static void setEndToStart(View target, String value, Context context) {
    setConstraint(target, value, END, START);
  }

  public static void setEndToEnd(View target, String value, Context context) {
    setConstraint(target, value, END, END);
  }

  public static void setLayoutMarginStart(View target, String value, Context context) {
    setMargin(target, START, value);
  }

  public static void setLayoutMarginEnd(View target, String value, Context context) {
    setMargin(target, END, value);
  }

  public static void setLayoutMarginLeft(View target, String value, Context context) {
    setMargin(target, LEFT, value);
  }

  public static void setLayoutMarginTop(View target, String value, Context context) {
    setMargin(target, TOP, value);
  }

  public static void setLayoutMarginRight(View target, String value, Context context) {
    setMargin(target, RIGHT, value);
  }

  public static void setLayoutMarginBottom(View target, String value, Context context) {
    setMargin(target, BOTTOM, value);
  }

  public static void setLayoutMarginBaseline(View target, String value, Context context) {
    setMargin(target, BASELINE, value);
  }

  public static void setLayoutGoneMarginStart(View target, String value, Context context) {
    setGoneMargin(target, START, value);
  }

  public static void setLayoutGoneMarginEnd(View target, String value, Context context) {
    setGoneMargin(target, END, value);
  }

  public static void setLayoutGoneMarginLeft(View target, String value, Context context) {
    setGoneMargin(target, LEFT, value);
  }

  public static void setLayoutGoneMarginTop(View target, String value, Context context) {
    setGoneMargin(target, TOP, value);
  }

  public static void setLayoutGoneMarginRight(View target, String value, Context context) {
    setGoneMargin(target, RIGHT, value);
  }

  public static void setLayoutGoneMarginBottom(View target, String value, Context context) {
    setGoneMargin(target, BOTTOM, value);
  }

  public static void setLayoutGoneMarginBaseline(View target, String value, Context context) {
    setGoneMargin(target, BASELINE, value);
  }

  private static float parseBias(String value) {
    return Math.max(0f, Math.min(1f, Float.parseFloat(value)));
  }

  public static void setHorizontalBias(View target, String value, Context context) {
    ConstraintLayout layout = getParentLayout(target);
    ConstraintSet set = cloneWithIds(layout);
    set.setHorizontalBias(target.getId(), parseBias(value));
    set.applyTo(layout);
  }

  public static void setVerticalBias(View target, String value, Context context) {
    ConstraintLayout layout = getParentLayout(target);
    ConstraintSet set = cloneWithIds(layout);
    set.setVerticalBias(target.getId(), parseBias(value));
    set.applyTo(layout);
  }
}