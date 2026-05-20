package com.itsaky.androidide.plugins.base

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class SafePluginLayoutInflater private constructor(
    original: LayoutInflater,
    context: Context,
    private val pluginId: String
) : LayoutInflater(original, context) {

    override fun onCreateView(name: String, attrs: AttributeSet): View {
        for (prefix in FRAMEWORK_PREFIXES) {
            try {
                return createView(name, prefix, attrs)
            } catch (_: ClassNotFoundException) {
                // try the next framework prefix
            }
        }
        return super.onCreateView(name, attrs)
    }

    override fun cloneInContext(newContext: Context): LayoutInflater {
        return SafePluginLayoutInflater(this, newContext, pluginId)
    }

    override fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View {
        return try {
            super.inflate(resource, root, attachToRoot)
        } catch (e: Exception) {
            PluginFragmentHelper.onPluginInflationError?.invoke(pluginId, e)
            createErrorView(root)
        }
    }

    private fun createErrorView(root: ViewGroup?): View {
        val ctx = context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(24), dp(48), dp(24), dp(48))

            addView(TextView(ctx).apply {
                text = "This plugin encountered an error and could not load its view."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF737373.toInt())
                gravity = Gravity.CENTER
            })
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    companion object {
        private val FRAMEWORK_PREFIXES = arrayOf(
            "android.widget.",
            "android.webkit.",
            "android.view.",
        )

        fun wrap(inflater: LayoutInflater, pluginId: String): SafePluginLayoutInflater {
            return SafePluginLayoutInflater(inflater, inflater.context, pluginId)
        }
    }
}
