package org.appdevforall.codeonthego.layouteditor.tools;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CalendarView;
import android.widget.SearchView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;

import org.apache.commons.text.StringEscapeUtils;
import org.appdevforall.codeonthego.layouteditor.editor.DesignEditor;
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class XmlLayoutGenerator {
  final StringBuilder builder = new StringBuilder();
  String TAB = "\t";
  boolean useSuperclasses;

  public String generate(@NonNull DesignEditor editor, boolean useSuperclasses) {
    this.useSuperclasses = useSuperclasses;

    // Clear builder to avoid accumulating content from previous calls
    builder.setLength(0);

    if (editor.getChildCount() == 0) {
      return "";
    }
    builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");

    return peek(editor.getChildAt(0), editor.getViewAttributeMap(), 0);
  }

  private String peek(View view, HashMap<View, AttributeMap> attributeMap, int depth) {
    if (attributeMap == null || view == null) return "";
    if (tryWriteInclude(view, attributeMap, depth)) {
      return builder.toString();
    }
    if (tryWriteFragment(view, attributeMap, depth)) {
      return builder.toString();
    }
    if (tryWriteMerge(view, attributeMap, depth)) {
      return builder.toString();
    }
    String indent = getIndent(depth);
    int nextDepth = depth;

    String className = getClassName(view, indent);

    List<String> keys =
            (attributeMap.get(view) != null) ? attributeMap.get(view).keySet() : new ArrayList<>();
    for (String key : keys) {
      builder.append(TAB).append(indent).append(key).append("=\"").append(StringEscapeUtils.escapeXml11(attributeMap.get(view).getValue(key))).append("\"\n");
    }

    builder.deleteCharAt(builder.length() - 1);

    if (view instanceof ViewGroup group) {
      if (!(group instanceof CalendarView)
              && !(group instanceof SearchView)
              && !(group instanceof NavigationView)
              && !(group instanceof BottomNavigationView)
              && !(group instanceof TabLayout)) {
        nextDepth++;

        if (group.getChildCount() > 0) {
          builder.append(">\n\n");

          for (int i = 0; i < group.getChildCount(); i++) {
            peek(group.getChildAt(i), attributeMap, nextDepth);
          }

          builder.append(indent).append("</").append(className).append(">\n\n");
        } else {
          builder.append(" />\n\n");
        }
      } else {
        builder.append(" />\n\n");
      }
    } else {
      builder.append(" />\n\n");
    }

    return builder.toString().trim();
  }

  @NonNull
  private String getClassName(View view, String indent) {
    String className =
            useSuperclasses ? view.getClass().getSuperclass().getName() : view.getClass().getName();

    if (useSuperclasses) {
      if (className.equals("android.widget.Toolbar")) {
        className = "androidx.appcompat.widget.Toolbar";
      }
      if (className.startsWith("android.widget.")) {
        className = className.replace("android.widget.", "");
      }
    }

    builder.append(indent).append("<").append(className).append("\n");
    return className;
  }

  private boolean tryWriteInclude(View view, HashMap<View, AttributeMap> attributeMap, int depth) {
    AttributeMap attrs = attributeMap.get(view);

    if (attrs != null && attrs.contains("tools:is_xml_include")) {
      String indent = getIndent(depth);
      builder.append(indent).append("<include");

      for (String key : attrs.keySet()) {
        if (key.equals("tools:is_xml_include")) continue;

        builder.append("\n").append(indent).append(TAB)
          .append(key).append("=\"")
          .append(StringEscapeUtils.escapeXml11(attrs.getValue(key)))
          .append("\"");
      }
      builder.append(" />\n\n");
      return true;
    }
    return false;
  }

  private boolean tryWriteFragment(View view, HashMap<View, AttributeMap> attributeMap, int depth) {
    AttributeMap attrs = attributeMap.get(view);

    if (attrs != null && attrs.contains("tools:is_xml_fragment")) {
      String indent = getIndent(depth);
      builder.append(indent).append("<fragment");

      for (String key : attrs.keySet()) {
        if (key.equals("tools:is_xml_fragment")) continue;

        builder.append("\n").append(indent).append(TAB)
          .append(key).append("=\"")
          .append(StringEscapeUtils.escapeXml11(attrs.getValue(key)))
          .append("\"");
      }
      builder.append(" />\n\n");
      return true;
    }
    return false;
  }

    private boolean tryWriteMerge(View view, HashMap<View, AttributeMap> attributeMap, int depth) {
        AttributeMap attrs = attributeMap.get(view);

        if (attrs != null && attrs.contains("tools:is_xml_merge")) {
            String indent = getIndent(depth);
            builder.append(indent).append("<merge");

            for (String key : attrs.keySet()) {
                if (key.equals("tools:is_xml_merge")) continue;

                builder.append("\n").append(indent).append(TAB)
                        .append(key).append("=\"")
                        .append(StringEscapeUtils.escapeXml11(attrs.getValue(key)))
                        .append("\"");
            }

            // Check if merge has children
            if (view instanceof ViewGroup group && group.getChildCount() > 0) {
                builder.append(">\n\n");

                for (int i = 0; i < group.getChildCount(); i++) {
                    peek(group.getChildAt(i), attributeMap, depth + 1);
                }

                builder.append(indent).append("</merge>\n\n");
            } else {
                // Handle empty merge
                builder.append(" />\n\n");
            }
            return true;
        }
        return false;
    }

  @NonNull
  private String getIndent(int depth) {
    return TAB.repeat(depth);
  }
}