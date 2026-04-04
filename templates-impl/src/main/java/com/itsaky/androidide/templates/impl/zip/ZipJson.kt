package com.itsaky.androidide.templates.impl.zip


data class TemplatesIndex(val templates: List<TemplateRef>)
data class TemplateRef(val path: String, val experimental: Boolean = false,)

data class TemplateJson(
    val name: String,
    val description: String?,
    val version: String?,
    val tooltipTag: String = "",
    val defaultSaveLocation: String? = null,
    val defaultAppName: String? = null,
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
    val packageName: IdentifierJson? = null,
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

