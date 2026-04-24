package org.appdevforall.codeonthego.layouteditor.managers;

import org.appdevforall.codeonthego.layouteditor.adapters.models.ValuesItem;
import org.appdevforall.codeonthego.layouteditor.tools.ValuesResourceParser;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ValuesManager {

  public static String getValueFromResources(String tag, String value, String path) {
    String resValueName = value.substring(value.indexOf("/") + 1);
    String result = null;
    try (FileInputStream stream = new FileInputStream(path)) {
      ValuesResourceParser parser = new ValuesResourceParser(stream, tag);

      for (ValuesItem item : parser.getValuesList()) {
        if (item.name.equals(resValueName)) {
          result = item.value;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return result;
  }
}
