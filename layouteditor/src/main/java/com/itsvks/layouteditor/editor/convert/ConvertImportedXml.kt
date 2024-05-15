package com.itsvks.layouteditor.editor.convert

import android.content.Context
import com.itsvks.layouteditor.utils.FileUtil
import org.json.JSONObject
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

class ConvertImportedXml(private val xml: String?) {

  fun getXmlConverted(context: Context): String? {
    var convertedXml = xml

    if (isWellFormed(xml)) {
      val pattern = "<([a-zA-Z0-9]+\\.)*([a-zA-Z0-9]+)"

      val matcher = Pattern.compile(pattern).matcher(
        xml.toString()
      )
      try {
        while (matcher.find()) {
          val fullTag = matcher.group(0)?.replace("<", "")
          val widgetName = matcher.group(2)

          val classes =
            JSONObject(FileUtil.readFromAsset("widgetclasses.json", context))

          val widgetClass = widgetName?.let { classes.getString(it) }
          if (convertedXml != null) {
            convertedXml = convertedXml.replace("<$fullTag", "<$widgetClass")
          }
          if (convertedXml != null) {
            convertedXml = convertedXml.replace("</$fullTag", "</$widgetClass")
          }
        }
      } catch (e: Exception) {
        e.printStackTrace()
        return null
      }
    } else {
      return null
    }
    return convertedXml
  }

  private fun isWellFormed(xml: String?): Boolean {
    try {
      val factory = DocumentBuilderFactory.newInstance()
      val builder = factory.newDocumentBuilder()
      val source = InputSource(StringReader(xml))
      builder.parse(source)
      return true
    } catch (e: Exception) {
      return false
    }
  }

  fun isLayoutFile(filePath: String): Boolean {
    val file = File(filePath)
    if (!file.exists() || !file.isFile) {
      return false
    }

    val layoutTags = arrayOf(
      "Button",
      "ImageButton",
      "ChipGroup",
      "Chip",
      "CheckBox",
      "RadioGroup",
      "RadioButton",
      "ToggleButton",
      "Switch",
      "FloatingActionButton",
      "Spinner",
      "ScrollView",
      "HorizontalScrollView",
      "NestedScrollView",
      "ViewPager",
      "CardView",
      "AppBarLayout",
      "BottomAppBar",
      "Toolbar",
      "MaterialToolbar",
      "TabItem",
      "RelativeLayout",
      "LinearLayout",
      "FrameLayout",
      "TableLayout",
      "TableRow",
      "Space",
      "GridLayout",
      "TabHost",
      "GridView",
      "TextView",
      "AutoCompleteTextView",
      "MultiAutoCompleteTextView",
      "CheckedTextView",
      "View",
      "ImageView",
      "WebView",
      "VideoView",
      "TextClock",
      "ProgressBar",
      "SeekBar",
      "RatingBar",
      "TextureView",
      "SurfaceView",
      "EditText",
      "DatePicker",
      "TimePicker",
      "Chronometer",
      "ViewFlipper",
      "ListView",
      "GridView",
      "SearchView",
      "RatingBar",
      "NumberPicker",
      "SeekBar",
      "ProgressBar",
      "QuickContactBadge",
      "Switch",
      "TextSwitcher",
      "ImageSwitcher",
      "AdapterViewFlipper",
      "AnalogClock",
      "DigitalClock",
      "ConstraintLayout"
    )

    try {
      val factory = DocumentBuilderFactory.newInstance()
      val builder = factory.newDocumentBuilder()
      val document = builder.parse(file)
      var rootTag = document.documentElement.tagName
      if (rootTag.contains(".")) rootTag = rootTag.substring(rootTag.lastIndexOf(".") + 1)

      for (layoutTag in layoutTags) {
        if (rootTag == layoutTag) {
          return true
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return false
  }
}
