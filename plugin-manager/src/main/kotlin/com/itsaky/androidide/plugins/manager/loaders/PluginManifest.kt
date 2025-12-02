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
    val sidebarItems: Int = 0
)

data class ExtensionInfo(
    @SerializedName("type")
    val type: String,
    
    @SerializedName("class")
    val className: String,
    
    @SerializedName("priority")
    val priority: Int = 0
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