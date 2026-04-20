package org.appdevforall.codeonthego.layouteditor.editor.validation

import android.content.Context
import android.view.ViewGroup
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.editor.palette.containers.ToolbarDesign
import org.appdevforall.codeonthego.layouteditor.editor.palette.layouts.FrameLayoutDesign

sealed class HierarchyResult {
    object Valid : HierarchyResult()
    data class Warning(val message: String) : HierarchyResult()
    data class Invalid(val errorMessage: String) : HierarchyResult()
}

private fun interface NestingRule {
    fun matches(child: String, parentName: String, parentView: ViewGroup): Boolean
}

class HierarchyValidator(private val context: Context) {

    /**
     * Rules that must block insertion because they are known to crash
     * or break the editor/runtime consistently.
     */
    private val blockingRules = listOf(

        // DrawerLayout / ViewPager inside restrictive parents
        NestingRule { child, _, parentView ->
            val needsExactly = child.contains("drawerlayout") || child.contains("viewpager")
            needsExactly && parentView.isCrashProneParent()
        }
    )

    /**
     * Rules that should not block insertion, but should inform the user
     * that the hierarchy may behave poorly.
     */
    private val warningRules = listOf(

        // Recycler-like views inside vertical ScrollView
        NestingRule { child, parentName, _ ->
            val isListOrGrid = child.contains("gridview") ||
                child.contains("listview") ||
                child.contains("recyclerview")
            val isVerticalScroll = parentName.contains("scrollview") &&
                !parentName.contains("horizontal")
            isListOrGrid && isVerticalScroll
        },

        // Vertical ScrollView inside vertical ScrollView
        NestingRule { child, parentName, _ ->
            val isVerticalScrollChild = child.contains("scrollview") &&
                !child.contains("horizontal")
            val isVerticalScrollParent = parentName.contains("scrollview") &&
                !parentName.contains("horizontal")
            isVerticalScrollChild && isVerticalScrollParent
        },

        // HorizontalScrollView inside HorizontalScrollView
        NestingRule { child, parentName, _ ->
            child.contains("horizontalscrollview") &&
                parentName.contains("horizontalscrollview")
        }
    )

    fun validate(childClassName: String, parent: ViewGroup): HierarchyResult {
        val cleanChild = childClassName.cleanWidgetName()
        val cleanParent = parent.cleanWidgetName()

        val lowerChild = cleanChild.lowercase()
        val lowerParent = cleanParent.lowercase()

        for (rule in blockingRules) {
            if (rule.matches(lowerChild, lowerParent, parent)) {
                val message = context.getString(
                    R.string.error_incompatible_hierarchy,
                    cleanChild,
                    cleanParent
                )
                return HierarchyResult.Invalid(message)
            }
        }

        for (rule in warningRules) {
            if (rule.matches(lowerChild, lowerParent, parent)) {
                val message = context.getString(
                    R.string.warning_problematic_hierarchy,
                    cleanChild,
                    cleanParent
                )
                return HierarchyResult.Warning(message)
            }
        }

        return HierarchyResult.Valid
    }

    private fun String.cleanWidgetName(): String =
        substringAfterLast('.').removeSuffix("Design")

    private fun ViewGroup.cleanWidgetName(): String = javaClass.name.cleanWidgetName()

    private fun ViewGroup.isCrashProneParent(): Boolean {
        val cleanParent = cleanWidgetName().lowercase()
        return this is ToolbarDesign ||
            this is FrameLayoutDesign ||
            cleanParent.contains("scrollview")
    }
}
