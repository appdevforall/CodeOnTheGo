package com.itsvks.layouteditor.editor.callers;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.widget.LinearLayoutCompat;

import com.itsvks.layouteditor.utils.Constants;

import java.util.HashMap;

public class LinearLayoutCaller {

  private static final HashMap<String, Integer> orientationMap = new HashMap<>();

  static {
    orientationMap.put("horizontal", LinearLayout.HORIZONTAL);
    orientationMap.put("vertical", LinearLayout.VERTICAL);
  }

  public static void setOrientation(View target, String value, Context context) {
    if (target instanceof LinearLayout)
      ((LinearLayout) target).setOrientation(orientationMap.get(value));
    else ((LinearLayoutCompat) target).setOrientation(orientationMap.get(value));
  }

  public static void setGravity(View target, String value, Context context) {
    String[] flags = value.split("\\|");
    int result = 0;

    for (String flag : flags) result |= Constants.gravityMap.get(flag);
    if (target instanceof LinearLayout) ((LinearLayout) target).setGravity(result);
    else ((LinearLayoutCompat) target).setGravity(result);
  }
}
