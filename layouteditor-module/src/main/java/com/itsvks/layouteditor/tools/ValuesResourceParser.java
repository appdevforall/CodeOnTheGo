package com.itsvks.layouteditor.tools;

import android.widget.TextView;
import com.itsvks.layouteditor.ProjectFile;
import com.itsvks.layouteditor.adapters.models.ValuesItem;
import com.itsvks.layouteditor.utils.FileUtil;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

public class ValuesResourceParser {
  public static final String TAG_STRING = "string";
  public static final String TAG_COLOR = "color";

  private List<ValuesItem> valuesList;

  public ValuesResourceParser(InputStream stream, String tag) {
    valuesList = new ArrayList<>();
    parseXML(stream, tag);
  }

  private void parseXML(InputStream stream, String tag) {
    String name = "";
    String value = "";
    try {
      XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
      factory.setNamespaceAware(true);
      XmlPullParser xpp = factory.newPullParser();

      xpp.setInput(stream, null);

      int eventType = xpp.getEventType();
      while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
          String tagName = xpp.getName();
          if (tagName.equalsIgnoreCase(tag)) {
            name = xpp.getAttributeValue(null, "name");
          }
        } else if (eventType == XmlPullParser.TEXT) {
          value = xpp.getText();
        } else if (eventType == XmlPullParser.END_TAG) {
          String tagName = xpp.getName();
          if (tagName.equalsIgnoreCase(tag)) {
            valuesList.add(new ValuesItem(name, value));
          }
        }
        eventType = xpp.next();
      }
      // createTextView(textView);
    } catch (XmlPullParserException | IOException e) {
      e.printStackTrace();
    }
  }

  public void createTextView(TextView textView) {
    StringBuilder builder = new StringBuilder();
    for (ValuesItem item : valuesList) {
      builder.append(item.name).append(" = ").append(item.value).append("\n");
    }
    textView.setText(builder.toString());
  }

  public List<ValuesItem> getValuesList() {
    return this.valuesList;
  }

  public void setValuesList(List<ValuesItem> valuesList) {
    this.valuesList = valuesList;
  }
}
