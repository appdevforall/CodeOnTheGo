package com.itsvks.layouteditor.editor.callers.text;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.TextView;
import com.itsvks.layouteditor.ProjectFile;
import com.itsvks.layouteditor.managers.DrawableManager;
import com.itsvks.layouteditor.managers.ProjectManager;
import com.itsvks.layouteditor.managers.ValuesManager;
import com.itsvks.layouteditor.tools.ValuesResourceParser;
import com.itsvks.layouteditor.utils.Constants;
import com.itsvks.layouteditor.utils.DimensionUtil;

public class TextViewCaller {
  public static void setText(View target, String value, Context context) {
    if (value.startsWith("@string/")) {
      ProjectFile project = ProjectManager.getInstance().getOpenedProject();

      value =
          ValuesManager.getValueFromResources(
              ValuesResourceParser.TAG_STRING, value, project.getStringsPath());
    }
    ((TextView) target).setText(value);
  }

  public static void setTextSize(View target, String value, Context context) {
    ((TextView) target).setTextSize(DimensionUtil.parse(value, context));
  }

  public static void setTextColor(View target, String value, Context context) {
    ((TextView) target).setTextColor(Color.parseColor(value));
  }

  public static void setGravity(View target, String value, Context context) {
    String[] flags = value.split("\\|");
    int result = 0;

    for (String flag : flags) {
      result |= Constants.gravityMap.get(flag);
    }

    ((TextView) target).setGravity(result);
  }

  public static void setCheckMark(View target, String value, Context context) {
    String name = value.replace("@drawable/", "");
    if (target instanceof CheckedTextView)
      ((CheckedTextView) target).setCheckMarkDrawable(DrawableManager.getDrawable(context, name));
  }

  public static void setChecked(View target, String value, Context context) {
    if (target instanceof CheckedTextView) {
      if (value.equals("true")) ((CheckedTextView) target).setChecked(true);
      else if (value.equals("false")) ((CheckedTextView) target).setChecked(false);
    }
  }

  public static void setTextStyle(View target, String value, Context context) {
    ((TextView) target).setTypeface(null, Constants.textStyleMap.get(value));
  }
}
