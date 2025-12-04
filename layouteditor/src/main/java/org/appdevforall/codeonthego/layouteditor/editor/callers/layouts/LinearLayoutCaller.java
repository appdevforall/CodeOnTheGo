package org.appdevforall.codeonthego.layouteditor.editor.callers.layouts;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

public class LinearLayoutCaller {
  public static void setWeightSum(View target, String value, Context context) {
    if (target instanceof LinearLayout) ((LinearLayout) target).setWeightSum(Float.valueOf(value));
  }
}
