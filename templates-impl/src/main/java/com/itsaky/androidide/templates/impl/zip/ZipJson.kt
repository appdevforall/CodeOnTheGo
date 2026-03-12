package com.itsaky.androidide.templates.impl.zip

import com.itsaky.androidide.templates.BooleanParameterBuilder
import com.itsaky.androidide.templates.CheckBoxWidget
import com.itsaky.androidide.templates.StringParameterBuilder
import com.itsaky.androidide.templates.TextFieldWidget
import com.itsaky.androidide.templates.Widget

data class TemplatesIndex(val templates: List<TemplateRef>)
data class TemplateRef(val path: String, val experimental: Boolean = false,)

//  data class TemplateJson(
//    val name: String,
//    val description: String?,
//    val widgets: List<WidgetJson> = emptyList(),
//    val language: Boolean? = true
//  )

data class TemplateJson(
  val name: String,
  val description: String?,
  val version: String?,
  val parameters: ParametersJson? = null,
  val system: SystemParametersJson? = null
)

data class ParametersJson(
  val required: RequiredParametersJson? = null,
  val optional: OptionalParametersJson? = null,
  val user: UserParametersJson? = null
)

data class RequiredParametersJson(
  val appName: IdentifierJson,
  val packageName: IdentifierJson,
  val saveLocation: IdentifierJson
)

data class OptionalParametersJson(
  val language: IdentifierJson? = null,
  val minsdk: IdentifierJson? = null
)

data class SystemParametersJson(
  val agpVersion: IdentifierJson,
  val kotlinVersion: IdentifierJson,
  val gradleVersion: IdentifierJson,
  val compileSdk: IdentifierJson,
  val targetSdk: IdentifierJson,
  val javaSourceCompat: IdentifierJson,
  val javaTargetCompat: IdentifierJson,
  val javaTarget: IdentifierJson
)

data class IdentifierJson(
  val identifier: String
)

data class UserParametersJson(
  val text: List<TextParameterJson> = emptyList(),
  val checkbox: List<CheckboxParameterJson> = emptyList()
)

data class TextParameterJson(
  val label: String,
  val identifier: String,
  val default: String? = null
)

data class CheckboxParameterJson(
  val label: String,
  val identifier: String,
  val default: Boolean? = null
)

data class WidgetJson(
  val type: String,
  val name: String,
  val id: String,
  val default: Any?,
  val options: List<String>?,
  val constraints: List<String>?
) {
  fun toWidget(): Widget<*>? {
    return when(type) {
      "string" -> {
        val param = StringParameterBuilder().apply {
          //this.name = this@WidgetJson.name
          //this.description = null
          //this.default = this@WidgetJson.default?.toString() ?: ""
          //this.key = this@WidgetJson.id
        }.build()
        TextFieldWidget(param)
      }
      "boolean" -> {
        val param = BooleanParameterBuilder().apply {
          //this.name = this@WidgetJson.name
          this.description = null
          this.default = this@WidgetJson.default?.toString()?.toBoolean() ?: false
          //this.key = this@WidgetJson.id
        }.build()
        CheckBoxWidget(param)
      }
      // Add more mappings as needed
      else -> null
    }
  }
}
