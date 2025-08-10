package org.appdevforall.codeonthego.layouteditor.managers;

import org.appdevforall.codeonthego.layouteditor.ProjectFile;
import org.appdevforall.codeonthego.layouteditor.adapters.models.ValuesItem;
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager;
import org.appdevforall.codeonthego.layouteditor.tools.ValuesResourceParser;
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ValuesManager {

  public static String getValueFromResources(String tag, String value, String path) {
    String resValueName = value.substring(value.indexOf("/") + 1);
    String result = null;
    try {
      ValuesResourceParser parser = new ValuesResourceParser(new FileInputStream(path), tag);

      for (ValuesItem item : parser.getValuesList()) {
        if (item.name.equals(resValueName)) {
          result = item.value;
        }
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return result;
  }
}
