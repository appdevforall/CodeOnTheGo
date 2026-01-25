package com.itsaky.androidide.templates.base

import com.itsaky.androidide.templates.ModuleTemplate
import com.itsaky.androidide.templates.RecipeExecutor
import com.itsaky.androidide.templates.base.util.AndroidManifestBuilder.ConfigurationType.APPLICATION_ATTR
import com.itsaky.androidide.templates.base.modules.android.ndkBuildGradleSrcKts
import com.itsaky.androidide.templates.base.modules.android.ndkBuildGradleSrcGroovy

import com.itsaky.androidide.templates.SrcSet
import com.itsaky.androidide.templates.base.util.SourceWriter
import java.io.File

class NdkModuleTemplateBuilder(
    private val ndkVersion: String? = null,
    private val abiFilters: List<String>? = null,
    private val cppFlags: String? = null
) : AndroidModuleTemplateBuilder() {

    fun srcNativeFilePath(srcSet: SrcSet, fileName: String): File {
        // place under src/main/cpp
        val cppDir = File(javaSrc(srcSet), "../cpp")
        if (!cppDir.exists()) {
            cppDir.mkdirs()
        }
        return File(cppDir, fileName)
    }

    inline fun writeCpp(
        writer: SourceWriter,
        fileName: String,
        crossinline cppSrcProvider: () -> String
    ) {
        val src = cppSrcProvider()
        if (src.isNotBlank() && fileName.isNotBlank()) {
            executor.save(src, srcNativeFilePath(SrcSet.Main, fileName))
        }
    }

    inline fun writeCMakeList(
        writer: SourceWriter,
        fileName: String,
        crossinline cmakeSrcProvider: () -> String
    ) {
        val src = cmakeSrcProvider()
        if (src.isNotBlank() && fileName.isNotBlank()) {
            executor.save(src, srcNativeFilePath(SrcSet.Main, fileName))
        }
    }

    override fun RecipeExecutor.buildGradle() {
        val gradleContent = if (data.useKts) {
            ndkBuildGradleSrcKts(
                isComposeModule,
                ndkVersion ?: "",
                abiFilters ?: emptyList(),
                cppFlags ?: ""
            )
        } else {
            ndkBuildGradleSrcGroovy(
                isComposeModule,
                ndkVersion ?: "",
                abiFilters ?: emptyList(),
                cppFlags ?: ""
            )
        }
        save(gradleContent, buildGradleFile())
    }
}


inline fun ProjectTemplateBuilder.defaultAppModuleWithNdk(
    name: String = ":app",
    ndkVersion: String? = null,
    abiFilters: List<String>? = null,
    cppFlags: String? = null,
    addAndroidX: Boolean = true,
    copyDefAssets: Boolean = true,
    crossinline block: NdkModuleTemplateBuilder.() -> Unit
) {
    val module = NdkModuleTemplateBuilder(ndkVersion, abiFilters, cppFlags).apply {
        _name = name

        templateName = 0
        thumb = 0

        preRecipe = commonPreRecipe { return@commonPreRecipe defModule }
        postRecipe = commonPostRecipe {
            if (copyDefAssets) {
                copyDefaultRes()

                manifest {
                    configure(APPLICATION_ATTR) {
                        androidAttribute("dataExtractionRules", "@xml/data_extraction_rules")
                        androidAttribute("fullBackupContent", "@xml/backup_rules")
                    }
                }
            }
        }

        if (addAndroidX) baseAndroidXDependencies()

        block()
    }.build() as ModuleTemplate

    modules.add(module)
}

