package com.itsvks.layouteditor.utils

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import androidx.annotation.RequiresApi

object Constants {
  const val CURRENT_LAYOUT: String = "currentLayout"

  @JvmField
  val gravityMap = HashMap<String, Int>()
  @JvmField
  val inputTypes = HashMap<String, Int>()
  @JvmField
  val imeOptions = HashMap<String, Int>()
  @JvmField
  val visibilityMap = HashMap<String, Int>()
  @JvmField
  val textStyleMap = HashMap<String, Int>()
  const val ATTRIBUTES_FILE = "attributes/attributes.json"
  const val PARENT_ATTRIBUTES_FILE = "attributes/parent_attributes.json"
  const val PALETTE_COMMON = "palette/common.json"
  const val PALETTE_TEXT = "palette/text.json"
  const val PALETTE_BUTTONS = "palette/buttons.json"
  const val PALETTE_WIDGETS = "palette/widgets.json"
  const val PALETTE_LAYOUTS = "palette/layouts.json"
  const val PALETTE_CONTAINERS = "palette/containers.json"
  const val PALETTE_GOOGLE = "palette/google.json"
  const val PALETTE_LEGACY = "palette/legacy.json"
  const val TAB_TITLE_VIEWS = "Views"
  const val TAB_TITLE_ANDROIDX = "AndroidX"
  const val TAB_TITLE_MATERIAL = "Material Design"
  const val TAB_TITLE_COMMON = "Common"
  const val TAB_TITLE_TEXT = "Text"
  const val TAB_TITLE_BUTTONS = "Buttons"
  const val TAB_TITLE_WIDGETS = "Widgets"
  const val TAB_TITLE_LAYOUTS = "Layouts"
  const val TAB_TITLE_CONTAINERS = "Containers"
  const val TAB_TITLE_GOOGLE = "Google"
  const val TAB_TITLE_LEGACY = "Legacy"
  const val KEY_ATTRIBUTE_NAME = "attributeName"
  const val KEY_CLASS_NAME = "className"
  const val KEY_METHOD_NAME = "methodName"
  const val KEY_ARGUMENT_TYPE = "argumentType"
  const val KEY_CAN_DELETE = "canDelete"
  const val KEY_CONSTANT = "constant"
  const val KEY_DEFAULT_VALUE = "defaultValue"
  const val KEY_DEFAULT_ATTRS = "defaultAttributes"
  const val ARGUMENT_TYPE_SIZE = "size"
  const val ARGUMENT_TYPE_DIMENSION = "dimension"
  const val ARGUMENT_TYPE_ID = "id"
  const val ARGUMENT_TYPE_VIEW = "view"
  const val ARGUMENT_TYPE_BOOLEAN = "boolean"
  const val ARGUMENT_TYPE_DRAWABLE = "drawable"
  const val ARGUMENT_TYPE_STRING = "string"
  const val ARGUMENT_TYPE_TEXT = "text"
  const val ARGUMENT_TYPE_INT = "int"
  const val ARGUMENT_TYPE_FLOAT = "float"
  const val ARGUMENT_TYPE_FLAG = "flag"
  const val ARGUMENT_TYPE_ENUM = "enum"
  const val ARGUMENT_TYPE_COLOR = "color"
  const val BLUEPRINT_DASH_COLOR = Color.WHITE
  @JvmField
  val BLUEPRINT_BACKGROUND_COLOR = Color.parseColor("#235C6F")
  @JvmField
  val DESIGN_DASH_COLOR = Color.parseColor("#1689F6")
  const val DESIGN_BACKGROUND_COLOR = Color.WHITE
  const val GITHUB_URL = "https://github.com/itsvks19/LayoutEditor"
  const val EXTRA_KEY_PROJECT = "project"
  const val EXTRA_KEY_LAYOUT = "layout"

  init {
    gravityMap["left"] = Gravity.START
    gravityMap["right"] = Gravity.END
    gravityMap["top"] = Gravity.TOP
    gravityMap["bottom"] = Gravity.BOTTOM
    gravityMap["center"] = Gravity.CENTER
    gravityMap["center_horizontal"] = Gravity.CENTER_HORIZONTAL
    gravityMap["center_vertical"] = Gravity.CENTER_VERTICAL
    gravityMap["fill"] = Gravity.FILL
    gravityMap["fill_horizontal"] = Gravity.FILL_HORIZONTAL
    gravityMap["fill_vertical"] = Gravity.FILL_VERTICAL
    gravityMap["clip_horizontal"] = Gravity.CLIP_HORIZONTAL
    gravityMap["clip_vertical"] = Gravity.CLIP_VERTICAL
    gravityMap["start"] = Gravity.START
    gravityMap["end"] = Gravity.END

    inputTypes["date"] =
      InputType.TYPE_DATETIME_VARIATION_DATE
    inputTypes["datetime"] = InputType.TYPE_CLASS_DATETIME
    inputTypes["none"] = InputType.TYPE_NULL
    inputTypes["number"] = InputType.TYPE_CLASS_NUMBER
    inputTypes["numberDecimal"] = InputType.TYPE_NUMBER_FLAG_DECIMAL
    inputTypes["numberSigned"] =
      InputType.TYPE_NUMBER_FLAG_SIGNED
    inputTypes["numberPassword"] =
      InputType.TYPE_NUMBER_VARIATION_PASSWORD
    inputTypes["phone"] = InputType.TYPE_CLASS_PHONE
    inputTypes["text"] = InputType.TYPE_CLASS_TEXT
    inputTypes["textAutoComplete"] =
      InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
    inputTypes["textAutoCorrect"] =
      InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
    inputTypes["textCapCharacters"] =
      InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    inputTypes["textCapSentences"] = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    inputTypes["textCapWords"] = InputType.TYPE_TEXT_FLAG_CAP_WORDS
    inputTypes["textEmailAddress"] = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    inputTypes["textEmailSubject"] = InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    inputTypes["textEnableTextConversionSuggestions"] =
      InputType.TYPE_TEXT_FLAG_ENABLE_TEXT_CONVERSION_SUGGESTIONS
    inputTypes["textFilter"] =
      InputType.TYPE_TEXT_VARIATION_FILTER
    inputTypes["textImeMultiLine"] = InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE
    inputTypes["textLongMessage"] = InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
    inputTypes["textMultiLine"] =
      InputType.TYPE_TEXT_FLAG_MULTI_LINE
    inputTypes["textNoSuggestions"] =
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    inputTypes["textPassword"] =
      InputType.TYPE_TEXT_VARIATION_PASSWORD
    inputTypes["textPersonName"] = InputType.TYPE_TEXT_VARIATION_PERSON_NAME
    inputTypes["textPhonetic"] =
      InputType.TYPE_TEXT_VARIATION_PHONETIC
    inputTypes["textPostalAddress"] = InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
    inputTypes["textShortMessage"] = InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
    inputTypes["textUri"] =
      InputType.TYPE_TEXT_VARIATION_URI
    inputTypes["textVisiblePassword"] = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    inputTypes["textWebEditText"] =
      InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
    inputTypes["textWebEmailAddress"] =
      InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    inputTypes["textWebPassword"] =
      InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    inputTypes["time"] =
      InputType.TYPE_DATETIME_VARIATION_TIME

    visibilityMap["visible"] = View.VISIBLE
    visibilityMap["invisible"] = View.INVISIBLE
    visibilityMap["gone"] = View.GONE

    textStyleMap["normal"] = Typeface.NORMAL
    textStyleMap["bold"] = Typeface.BOLD
    textStyleMap["italic"] = Typeface.ITALIC
    textStyleMap["bold|italic"] = Typeface.BOLD_ITALIC
  }
}
