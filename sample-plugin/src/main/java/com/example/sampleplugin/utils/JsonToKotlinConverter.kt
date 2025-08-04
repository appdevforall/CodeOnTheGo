package com.example.sampleplugin.utils

import org.json.JSONArray
import org.json.JSONObject

object JsonToKotlinConverter {

    enum class Language {
        KOTLIN, JAVA
    }

    private val generatedClasses = mutableSetOf<String>()

    fun convertToDataClass(jsonString: String, className: String, packageName: String, language: Language): String {
        generatedClasses.clear()
        val jsonObject = JSONObject(jsonString)
        val allClasses = mutableListOf<String>()

        // Generate the main class and collect all nested classes
        generateDataClass(jsonObject, className, allClasses, language)

        // Build the complete file content
        val builder = StringBuilder()

        if (packageName.isNotEmpty()) {
            builder.appendLine("package $packageName;")
            builder.appendLine()
        }

        // Add imports based on language
        when (language) {
            Language.KOTLIN -> {
                builder.appendLine("import kotlinx.serialization.Serializable")
                builder.appendLine("import com.google.gson.annotations.SerializedName")
            }
            Language.JAVA -> {
                builder.appendLine("import java.util.List;")
                builder.appendLine("import java.util.ArrayList;")
            }
        }
        builder.appendLine()

        // Add all classes to the same file
        builder.append(allClasses.joinToString("\n\n"))

        return builder.toString()
    }

    private fun generateDataClass(jsonObject: JSONObject, className: String, allClasses: MutableList<String>, language: Language): String {
        if (generatedClasses.contains(className)) {
            return className
        }
        generatedClasses.add(className)

        val builder = StringBuilder()

        when (language) {
            Language.KOTLIN -> generateKotlinClass(jsonObject, className, builder, allClasses)
            Language.JAVA -> generateJavaClass(jsonObject, className, builder, allClasses)
        }

        allClasses.add(builder.toString())
        return className
    }

    private fun generateKotlinClass(jsonObject: JSONObject, className: String, builder: StringBuilder, allClasses: MutableList<String>) {
        builder.appendLine("@Serializable")
        builder.append("data class $className(")

        val properties = mutableListOf<String>()
        val keys = jsonObject.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            val kotlinType = getKotlinType(value, key.toCamelCase().capitalize(), allClasses)
            val propertyName = key.toCamelCase()

            val property = buildString {
                appendLine()
                append("    @SerializedName(\"$key\")")
                appendLine()
                append("    val $propertyName: $kotlinType")

                when (value) {
                    is String -> append(" = \"\"")
                    is Boolean -> append(" = false")
                    is Int -> append(" = 0")
                    is Double -> {
                        if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                            append(" = 0")
                        } else {
                            append(" = 0.0")
                        }
                    }
                    is JSONArray -> append(" = emptyList()")
                    is JSONObject -> append(" = $kotlinType()")
                    else -> append("?")
                }
            }

            properties.add(property)
        }

        builder.append(properties.joinToString(","))
        builder.appendLine()
        builder.appendLine(")")
    }

    private fun generateJavaClass(jsonObject: JSONObject, className: String, builder: StringBuilder, allClasses: MutableList<String>) {
        builder.appendLine("public class $className {")

        val properties = mutableListOf<String>()
        val keys = jsonObject.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            val javaType = getJavaType(value, key.toCamelCase().capitalize(), allClasses)
            val propertyName = key.toCamelCase()

            // Field declaration
            val property = buildString {
                appendLine()
                append("    private $javaType $propertyName")

                when (value) {
                    is String -> append(" = \"\"")
                    is Boolean -> append(" = false")
                    is Int -> append(" = 0")
                    is Double -> {
                        if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                            append(" = 0")
                        } else {
                            append(" = 0.0")
                        }
                    }
                    is JSONArray -> append(" = new ArrayList<>()")
                    is JSONObject -> append(" = new $javaType()")
                    else -> append(" = null")
                }
                append(";")
            }

            properties.add(property)
        }

        builder.append(properties.joinToString(""))

        // Add constructors, getters, and setters
        generateJavaConstructorAndMethods(jsonObject, className, builder)

        builder.appendLine()
        builder.append("}")
    }

    private fun generateJavaConstructorAndMethods(jsonObject: JSONObject, className: String, builder: StringBuilder) {
        builder.appendLine()
        builder.appendLine("    // Default constructor")
        builder.appendLine("    public $className() {}")

        // Getters and Setters
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            val javaType = getJavaType(value, key.toCamelCase().capitalize(), mutableListOf())
            val propertyName = key.toCamelCase()
            val capitalizedPropertyName = propertyName.capitalize()

            builder.appendLine()
            builder.appendLine("    public $javaType get$capitalizedPropertyName() {")
            builder.appendLine("        return $propertyName;")
            builder.appendLine("    }")

            builder.appendLine()
            builder.appendLine("    public void set$capitalizedPropertyName($javaType $propertyName) {")
            builder.appendLine("        this.$propertyName = $propertyName;")
            builder.appendLine("    }")
        }
    }

    private fun getKotlinType(value: Any, suggestedClassName: String, allClasses: MutableList<String>): String {
        return when (value) {
            is String -> "String"
            is Boolean -> "Boolean"
            is Int -> "Int"
            is Double -> {
                if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                    "Int"
                } else {
                    "Double"
                }
            }
            is JSONArray -> handleKotlinArray(value, suggestedClassName, allClasses)
            is JSONObject -> {
                generateDataClass(value, suggestedClassName, allClasses, Language.KOTLIN)
                suggestedClassName
            }
            else -> "Any"
        }
    }

    private fun getJavaType(value: Any, suggestedClassName: String, allClasses: MutableList<String>): String {
        return when (value) {
            is String -> "String"
            is Boolean -> "Boolean"
            is Int -> "Integer"
            is Double -> {
                if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                    "Integer"
                } else {
                    "Double"
                }
            }
            is JSONArray -> handleJavaArray(value, suggestedClassName, allClasses)
            is JSONObject -> {
                generateDataClass(value, suggestedClassName, allClasses, Language.JAVA)
                suggestedClassName
            }
            else -> "Object"
        }
    }

    private fun handleKotlinArray(jsonArray: JSONArray, suggestedClassName: String, allClasses: MutableList<String>): String {
        if (jsonArray.length() == 0) {
            return "List<Any>"
        }

        val firstItem = jsonArray.get(0)
        return when (firstItem) {
            is String -> "List<String>"
            is Boolean -> "List<Boolean>"
            is Int -> "List<Int>"
            is Double -> {
                if (firstItem % 1.0 == 0.0 && firstItem >= Int.MIN_VALUE && firstItem <= Int.MAX_VALUE) {
                    "List<Int>"
                } else {
                    "List<Double>"
                }
            }
            is JSONObject -> {
                val itemClassName = "${suggestedClassName}Item"
                generateDataClass(firstItem, itemClassName, allClasses, Language.KOTLIN)
                "List<$itemClassName>"
            }
            is JSONArray -> {
                val innerType = handleKotlinArray(firstItem, "${suggestedClassName}Item", allClasses)
                "List<$innerType>"
            }
            else -> "List<Any>"
        }
    }

    private fun handleJavaArray(jsonArray: JSONArray, suggestedClassName: String, allClasses: MutableList<String>): String {
        if (jsonArray.length() == 0) {
            return "List<Object>"
        }

        val firstItem = jsonArray.get(0)
        return when (firstItem) {
            is String -> "List<String>"
            is Boolean -> "List<Boolean>"
            is Int -> "List<Integer>"
            is Double -> {
                if (firstItem % 1.0 == 0.0 && firstItem >= Int.MIN_VALUE && firstItem <= Int.MAX_VALUE) {
                    "List<Integer>"
                } else {
                    "List<Double>"
                }
            }
            is JSONObject -> {
                val itemClassName = "${suggestedClassName}Item"
                generateDataClass(firstItem, itemClassName, allClasses, Language.JAVA)
                "List<$itemClassName>"
            }
            is JSONArray -> {
                val innerType = handleJavaArray(firstItem, "${suggestedClassName}Item", allClasses)
                "List<$innerType>"
            }
            else -> "List<Object>"
        }
    }

    private fun String.toCamelCase(): String {
        return this.split("_", "-", " ")
            .mapIndexed { index, word ->
                if (index == 0) word.lowercase()
                else word.replaceFirstChar { it.uppercase() }
            }
            .joinToString("")
    }

    private fun String.capitalize(): String {
        return this.replaceFirstChar { it.uppercase() }
    }

    fun getSuggestedFilePath(className: String, packageName: String, language: Language): String {
        val extension = when (language) {
            Language.KOTLIN -> "kt"
            Language.JAVA -> "java"
        }

        return if (packageName.isNotEmpty()) {
            "app/src/main/java/${packageName.replace('.', '/')}/$className.$extension"
        } else {
            "app/src/main/java/$className.$extension"
        }
    }

    // Convenience methods for backward compatibility
    fun convertToKotlinDataClass(jsonString: String, className: String, packageName: String): String {
        return convertToDataClass(jsonString, className, packageName, Language.KOTLIN)
    }

    fun convertToJavaClass(jsonString: String, className: String, packageName: String): String {
        return convertToDataClass(jsonString, className, packageName, Language.JAVA)
    }
}