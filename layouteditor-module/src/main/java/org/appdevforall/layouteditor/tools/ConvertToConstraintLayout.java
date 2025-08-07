package com.itsvks.layouteditor.tools;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.constraintlayout.widget.ConstraintLayout;

public class ConvertToConstraintLayout extends ConstraintLayout {

  public ConvertToConstraintLayout(Context context) {
    super(context);
    init(context, null);
  }

  public ConvertToConstraintLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context, attrs);
  }

  public ConvertToConstraintLayout(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init(context, attrs);
  }

  private void init(Context context, AttributeSet attrs) {
    setLayoutParams(
        new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    if (getChildCount() > 0) {
      View view = getChildAt(0);
      removeView(view);
      addView(
          view,
          new LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
  }

  public static ConstraintLayout convertLayout(ViewGroup viewGroup) {
    ConvertToConstraintLayout convertToConstraintLayout =
        new ConvertToConstraintLayout(viewGroup.getContext());
    ViewGroup.LayoutParams params = viewGroup.getLayoutParams();
    viewGroup.removeView(viewGroup);
    viewGroup.addView(convertToConstraintLayout, params);
    for (int i = 0; i < viewGroup.getChildCount(); i++) {
      View child = viewGroup.getChildAt(i);
      ViewGroup.LayoutParams childParams = child.getLayoutParams();
      if (childParams instanceof RelativeLayout.LayoutParams) {
        RelativeLayout.LayoutParams relativeParams = (RelativeLayout.LayoutParams) childParams;
        int[] rules = relativeParams.getRules();
        ConstraintLayout.LayoutParams newParams =
            new ConstraintLayout.LayoutParams(childParams.width, childParams.height);
        if (rules[RelativeLayout.ALIGN_PARENT_TOP] == RelativeLayout.TRUE) {
          newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        } else if (rules[RelativeLayout.ALIGN_PARENT_BOTTOM] == RelativeLayout.TRUE) {
          newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        } else if (rules[RelativeLayout.ALIGN_PARENT_LEFT] == RelativeLayout.TRUE) {
          newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;
        } else if (rules[RelativeLayout.ALIGN_PARENT_RIGHT] == RelativeLayout.TRUE) {
          newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID;
        } else if (rules[RelativeLayout.CENTER_HORIZONTAL] == RelativeLayout.TRUE) {
          newParams.horizontalBias = 0.5f;
          newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;
          newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID;
        } else if (rules[RelativeLayout.CENTER_VERTICAL] == RelativeLayout.TRUE) {
          newParams.verticalBias = 0.5f;
          newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
          newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        }
        convertToConstraintLayout.addView(child, newParams);
      } else {
        convertToConstraintLayout.addView(child, childParams);
      }
    }
    return convertToConstraintLayout;
  }
}
