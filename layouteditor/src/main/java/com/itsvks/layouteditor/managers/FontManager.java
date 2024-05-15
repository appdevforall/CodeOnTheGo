package com.itsvks.layouteditor.managers;

import android.graphics.Typeface;
import com.itsvks.layouteditor.utils.FileUtil;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class FontManager {
  private static Map<String, String> items = new HashMap<>();

  public static void loadFromFiles(File[] files) {
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

  public static Typeface getFont(String key) {
    return Typeface.createFromFile(items.get(key));
  }

  public static Set<String> keySet() {
    return items.keySet();
  }
  
  public static void clear() {
    items.clear();
  }
}
