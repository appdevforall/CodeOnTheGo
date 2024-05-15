package com.itsvks.layouteditor.managers;

import android.content.Context;
import android.graphics.drawable.Drawable;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.itsvks.layouteditor.utils.FileUtil;

import com.itsvks.layouteditor.utils.Utils;
import java.io.File;
import java.util.HashMap;
import java.util.Set;

public class DrawableManager {
  private static final HashMap<String, String> items = new HashMap<>();

  public static void loadFromFiles(@NonNull File[] files) {
    items.clear();

    for (File f : files) {
      String path = f.getPath();
      String name = FileUtil.getLastSegmentFromPath(path);
      name = name.substring(0, name.lastIndexOf("."));

      items.put(name, path);
    }
  }

  public static boolean contains(String name) {
    return items.containsKey(name);
  }

  public static Drawable getDrawable(Context context, String key) {
    return items.get(key).endsWith(".xml")
        ? Utils.getVectorDrawableAsync(context, Uri.fromFile(new File(items.get(key))))
        : Drawable.createFromPath(items.get(key));
  }

  public static Set<String> keySet() {
    return items.keySet();
  }

  public static void clear() {
    items.clear();
  }
}
