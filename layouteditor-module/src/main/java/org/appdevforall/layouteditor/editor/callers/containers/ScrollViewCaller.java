package com.itsvks.layouteditor.editor.callers.containers;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

public class ScrollViewCaller {
  public static void setFillViewport(View target, String value, Context context) {
    if (value.equals("true")) ((ScrollView) target).setFillViewport(true);
    else if (value.equals("false")) ((ScrollView) target).setFillViewport(false);
  }
}
