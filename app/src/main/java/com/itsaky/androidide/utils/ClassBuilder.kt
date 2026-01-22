/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itsaky.androidide.preferences.utils.indentationString
import com.itsaky.androidide.utils.ClassBuilder.SourceLanguage.JAVA
import com.itsaky.androidide.utils.ClassBuilder.SourceLanguage.KOTLIN
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import jdkx.lang.model.element.Modifier.PROTECTED
import jdkx.lang.model.element.Modifier.PUBLIC

object ClassBuilder {

  enum class SourceLanguage { JAVA, KOTLIN }

  @JvmStatic
  fun createClass(packageName: String, className: String, language: SourceLanguage = JAVA): String {
    return when (language) {
      JAVA -> toJavaFile(packageName, newClassSpec(className)).toString()
      KOTLIN -> buildKotlinFile(packageName) { appendLine("class $className {"); appendLine("}") }
    }
  }

  private fun toJavaFile(
    packageName: String,
    type: TypeSpec,
    block: JavaFile.Builder.() -> Unit = {}
  ): JavaFile {
    return JavaFile.builder(packageName, type)
      .indent(indentationString)
      .apply { block(this) }
      .build()
  }

  private fun newClassSpec(className: String): TypeSpec {
    return TypeSpec.classBuilder(className).addModifiers(PUBLIC).build()
  }

  @JvmStatic
  fun createInterface(packageName: String, className: String, language: SourceLanguage = JAVA): String {
    return when (language) {
      JAVA -> toJavaFile(packageName, newInterfaceSpec(className)).toString()
      KOTLIN -> buildKotlinFile(packageName) { appendLine("interface $className {"); appendLine("}") }
    }
  }

  private fun newInterfaceSpec(className: String): TypeSpec {
    return TypeSpec.interfaceBuilder(className).addModifiers(PUBLIC).build()
  }

  @JvmStatic
  fun createEnum(packageName: String, className: String, language: SourceLanguage = JAVA): String {
    return when (language) {
      JAVA -> toJavaFile(packageName, newEnumSpec(className)).toString()
      KOTLIN -> buildKotlinFile(packageName) {
        appendLine("enum class $className {")
        appendLine("${indentationString}ENUM_DECLARED")
        appendLine("}")
      }
    }
  }

  private fun newEnumSpec(className: String): TypeSpec {
    return TypeSpec.enumBuilder(className)
      .addModifiers(PUBLIC)
      .addEnumConstant("ENUM_DECLARED")
      .build()
  }

  @JvmStatic
  fun createActivity(
    packageName: String,
    className: String,
    appCompatActivity: Boolean = true,
    language: SourceLanguage = JAVA
  ): String {
    return when (language) {
      JAVA -> createJavaActivity(packageName, className, appCompatActivity)
      KOTLIN -> createKotlinActivity(packageName, className, appCompatActivity)
    }
  }

  private fun createJavaActivity(packageName: String, className: String, appCompatActivity: Boolean): String {
    val onCreate =
      MethodSpec.methodBuilder("onCreate")
        .addAnnotation(Override::class.java)
        .addModifiers(PROTECTED)
        .addParameter(Bundle::class.java, "savedInstanceState")
        .addStatement("super.onCreate(savedInstanceState)")
        .build()
    val activity =
      newClassSpec(className)
        .toBuilder()
        .superclass(if (appCompatActivity) AppCompatActivity::class.java else Activity::class.java)
        .addMethod(onCreate)
    return toJavaFile(packageName, activity.build()) { skipJavaLangImports(true) }.toString()
  }

  private fun createKotlinActivity(packageName: String, className: String, appCompatActivity: Boolean): String {
    val superClass = if (appCompatActivity) "AppCompatActivity" else "Activity"
    val import = if (appCompatActivity) "androidx.appcompat.app.AppCompatActivity" else "android.app.Activity"
    return buildString {
      if (packageName.isNotEmpty()) appendLine("package $packageName")
      appendLine()
      appendLine("import android.os.Bundle")
      appendLine("import $import")
      appendLine()
      appendLine("class $className : $superClass() {")
      appendLine()
      appendLine("${indentationString}override fun onCreate(savedInstanceState: Bundle?) {")
      appendLine("${indentationString}${indentationString}super.onCreate(savedInstanceState)")
      appendLine("$indentationString}")
      appendLine("}")
    }
  }

  private fun buildKotlinFile(packageName: String, block: StringBuilder.() -> Unit): String {
    return buildString {
      if (packageName.isNotEmpty()) appendLine("package $packageName")
      appendLine()
      block()
    }
  }
}
