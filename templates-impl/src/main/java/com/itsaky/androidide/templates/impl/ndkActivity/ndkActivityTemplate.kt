package com.itsaky.androidide.templates.impl.ndkActivity

import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.base.AndroidModuleTemplateBuilder
import com.itsaky.androidide.templates.base.defaultAppModuleWithNdk
import com.itsaky.androidide.templates.base.util.AndroidModuleResManager.ResourceType.LAYOUT
import com.itsaky.androidide.templates.base.util.SourceWriter
import com.itsaky.androidide.templates.impl.R
import com.itsaky.androidide.templates.impl.base.createRecipe
import com.itsaky.androidide.templates.impl.base.emptyThemesAndColors
import com.itsaky.androidide.templates.impl.base.writeMainActivity
import com.itsaky.androidide.templates.impl.baseProjectImpl
import com.itsaky.androidide.templates.impl.emptyActivity.emptyLayoutSrc
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags

fun ndkActivityProject(): ProjectTemplate? {

    if (!FeatureFlags.isNdkEnabled) return null

    if (!Environment.NDK_DIR.exists()) {
        return null
    }

    return baseProjectImpl {
        templateName = R.string.template_ndk
        thumb = R.drawable.template_ndk_activity
        tooltipTag = TooltipTag.TEMPLATE_NDK_ACTIVITY

        defaultAppModuleWithNdk(
            ndkVersion = "29.0.14206865",
            abiFilters = listOf("arm64-v8a"),
            cppFlags = "-std=c++17"
        )
        {
            recipe = createRecipe {
                sources {
                    writeNdkActivity(this)
                    writeCpp(this, fileName = "native-lib.cpp") { ndkCpp() }
                    writeCMakeList(this, fileName = "CMakeLists.txt") { ndkCMakeLists() }
                }

                res { writeNdkActivity() }
            }
        }
    }
}


internal fun AndroidModuleTemplateBuilder.writeNdkActivity() {
    res.apply {
        // layout/activity_main.xml
        writeXmlResource("activity_main", LAYOUT, source = ::emptyLayoutSrc)
        emptyThemesAndColors()
    }
}

internal fun AndroidModuleTemplateBuilder.writeNdkActivity(
    writer: SourceWriter
) {
    writeMainActivity(writer, ::ndkActivitySrcKt, ::ndkActivitySrcJava)
}



