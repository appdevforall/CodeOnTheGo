package com.itsaky.androidide.plugins.manager.loaders

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.InputStreamReader
import java.util.jar.JarFile

data class PluginManifest(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("version")
    val version: String,
    
    @SerializedName("description")
    val description: String,
    
    @SerializedName("author")
    val author: String,
    
    @SerializedName("main_class")
    val mainClass: String,
    
    @SerializedName("min_ide_version")
    val minIdeVersion: String,
    
    @SerializedName("max_ide_version")
    val maxIdeVersion: String? = null,
    
    @SerializedName("permissions")
    val permissions: List<String> = emptyList(),
    
    @SerializedName("dependencies")
    val dependencies: List<String> = emptyList(),
    
    @SerializedName("extensions")
    val extensions: List<ExtensionInfo> = emptyList(),

    @SerializedName("sidebar_items")
    val sidebarItems: Int = 0,

    @SerializedName("build_actions")
    val buildActions: List<ManifestBuildAction> = emptyList()
)

data class ExtensionInfo(
    @SerializedName("type")
    val type: String,

    @SerializedName("class")
    val className: String,

    @SerializedName("priority")
    val priority: Int = 0
)

data class ManifestBuildAction(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("description")
    val description: String = "",
    @SerializedName("category")
    val category: String = "CUSTOM",
    @SerializedName("command")
    val command: String? = null,
    @SerializedName("arguments")
    val arguments: List<String> = emptyList(),
    @SerializedName("gradle_task")
    val gradleTask: String? = null,
    @SerializedName("working_directory")
    val workingDirectory: String? = null,
    @SerializedName("environment")
    val environment: Map<String, String> = emptyMap(),
    @SerializedName("timeout_ms")
    val timeoutMs: Long = 600_000
)

object PluginManifestParser {
    private val gson = Gson()
    
    fun parseFromJar(jarFile: File): PluginManifest? {
        return try {
            JarFile(jarFile).use { jar ->
                val entry = jar.getJarEntry("plugin.json")
                    ?: jar.getJarEntry("META-INF/plugin.json")
                    ?: return null
                
                val inputStream = jar.getInputStream(entry)
                val reader = InputStreamReader(inputStream)
                gson.fromJson(reader, PluginManifest::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    fun parseFromString(json: String): PluginManifest? {
        return try {
            gson.fromJson(json, PluginManifest::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    fun toJson(manifest: PluginManifest): String {
        return gson.toJson(manifest)
    }
}