package org.appdevforall.codeonthego.layouteditor.tools;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CalendarView;
import android.widget.SearchView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import org.appdevforall.codeonthego.layouteditor.editor.DesignEditor;
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap;

import org.apache.commons.text.StringEscapeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class XmlLayoutGenerator {
  final StringBuilder builder = new StringBuilder();
  String TAB = "\t";

  boolean useSuperclasses;

  public String generate(@NonNull DesignEditor editor, boolean useSuperclasses) {
    this.useSuperclasses = useSuperclasses;

    if (editor.getChildCount() == 0) {
      return "";
    }
    builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");

    return peek(editor.getChildAt(0), editor.getViewAttributeMap(), 0);
  }

  private String peek(View view, HashMap<View, AttributeMap> attributeMap, int depth) {
    if (attributeMap == null || view == null) return "";
    String indent = getIndent(depth);
    int nextDepth = depth;

    String className = getClassName(view, indent);

    if (depth == 0) {
      builder.append(TAB).append("xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
      builder.append(TAB).append("xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n");
    }

    List<String> keys =
      (attributeMap.get(view) != null) ? attributeMap.get(view).keySet() : new ArrayList<>();
    List<String> values =
      (attributeMap.get(view) != null) ? attributeMap.get(view).values() : new ArrayList<>();
    for (String key : keys) {
      // If the value contains special characters it will be converted
      builder.append(TAB).append(indent).append(key).append("=\"").append(StringEscapeUtils.escapeXml11(attributeMap.get(view).getValue(key))).append("\"\n");
    }

    builder.deleteCharAt(builder.length() - 1);

    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      if (!(group instanceof CalendarView)
        && !(group instanceof SearchView)
        && !(group instanceof NavigationView)
        && !(group instanceof BottomNavigationView)
        && !(group instanceof TabLayout)) {
        nextDepth++;

        if (group.getChildCount() > 0) {
          builder.append(">\n");

          for (int i = 0; i < group.getChildCount(); i++) {
            peek(group.getChildAt(i), attributeMap, nextDepth);
          }

          builder.append(indent).append("</").append(className).append(">\n");
        } else {
          builder.append(" />\n");
        }
      } else {
        builder.append(" />\n");
      }
    } else {
      builder.append(" />\n");
    }

    return builder.toString().trim();
  }

  @NonNull
  private String getClassName(View view, String indent) {
    String className =
      useSuperclasses ? view.getClass().getSuperclass().getName() : view.getClass().getName();

    if (useSuperclasses) {
      if (className.startsWith("android.widget.")) {
        className = className.replace("android.widget.", "");
      }
    }

    builder.append(indent).append("<").append(className).append("\n");
    return className;
  }

  @NonNull
  private String getIndent(int depth) {
    return TAB.repeat(depth);
  }
}
