package com.itsvks.layouteditor.vectormaster.utilities;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import com.itsvks.layouteditor.vectormaster.DefaultValues;

import java.lang.reflect.Field;

public class Utils {

  public static int getColorFromStringTheme(String value, boolean useLightTheme) {
    int color = getColorFromString(value, useLightTheme);

    if (color == 0xFFFFFFFF
        || color == 0xFF000000
        || value.startsWith("@android:color/white")
        || value.startsWith("@android:color/black")) {
      return useLightTheme
          ? DefaultValues.PATH_FILL_COLOR_BLACK
          : DefaultValues.PATH_FILL_COLOR_WHITE;
    }

    return color;
  }

  public static int getColorFromString(String value, boolean useLightTheme) {
    if (isAttributeInValid(value)) {
      return useLightTheme
          ? DefaultValues.PATH_FILL_COLOR_BLACK
          : DefaultValues.PATH_FILL_COLOR_WHITE;
    } else if (value.length() == 2) {
      return Color.parseColor(
          "#"
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(1));
    } else if (value.length() == 4) {
      return Color.parseColor(
          "#"
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(2)
              + value.charAt(2)
              + value.charAt(3)
              + value.charAt(3));
    } else if (value.length() == 7 || /*value.length() == 9 ||*/ value.startsWith("#")) {
      return Color.parseColor(value);
    } else if (value.length() == 9) {
      return Color.parseColor(value);
    } else if (value.startsWith("@android:color/")) {
      Object color = getAndroidColor(android.R.color.class.getName(), value.substring(15));
      if (color != null) {
        return (Integer) color;
      }
    } else {
      return useLightTheme
          ? DefaultValues.PATH_FILL_COLOR_BLACK
          : DefaultValues.PATH_FILL_COLOR_WHITE;
    }
    return useLightTheme
        ? DefaultValues.PATH_FILL_COLOR_BLACK
        : DefaultValues.PATH_FILL_COLOR_WHITE;
  }

  public static boolean isAttributeInValid(String value) {
    return value.startsWith("@/") || value.startsWith("?");
  }

  public static Path.FillType getFillTypeFromString(String value) {
    switch (value) {
      case "nonZero":
        return Path.FillType.WINDING;
      case "evenOdd":
        return Path.FillType.EVEN_ODD;
      default:
        return Path.FillType.WINDING;
    }
  }

  public static Paint.Cap getLineCapFromString(String value) {
    switch (value) {
      case "butt":
        return Paint.Cap.BUTT;
      case "round":
        return Paint.Cap.ROUND;
      case "square":
        return Paint.Cap.SQUARE;
      default:
        return Paint.Cap.BUTT;
    }
  }

  public static Paint.Join getLineJoinFromString(String value) {
    switch (value) {
      case "miter":
        return Paint.Join.MITER;
      case "round":
        return Paint.Join.ROUND;
      case "bevel":
        return Paint.Join.BEVEL;
      default:
        return Paint.Join.MITER;
    }
  }

  public static int getAlphaFromFloat(float value) {
    int newValue = (int) (255 * value);
    return Math.min(255, newValue);
  }

  public static float getAlphaFromInt(int value) {
    return (((float) value) / 255.0f);
  }

  public static int dpToPx(int dp) {
    return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
  }

  public static int pxToDp(int px) {
    return (int) (px / Resources.getSystem().getDisplayMetrics().density);
  }

  public static float getFloatFromDimensionString(String value) {
    if (value.contains("dip")) return Float.parseFloat(value.substring(0, value.length() - 3));
    else return Float.parseFloat(value.substring(0, value.length() - 2));
  }

  public static boolean isEqual(Object a, Object b) {
    return a == null && b == null || !(a == null || b == null) && a.equals(b);
  }

  public static Object getAndroidColor(String clazz, String colorName) {
    Field declaratedField;
    try {
      declaratedField = Class.forName(clazz).getField(colorName);
      declaratedField.setAccessible(true);
      return declaratedField.get(colorName);
    } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
      e.printStackTrace();
    }
    return null;
  }
}
