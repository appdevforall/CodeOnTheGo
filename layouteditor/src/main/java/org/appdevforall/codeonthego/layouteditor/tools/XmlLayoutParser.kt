package org.appdevforall.codeonthego.layouteditor.tools

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeInitializer
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap
import org.appdevforall.codeonthego.layouteditor.editor.positioning.restorePositionsAfterLoad
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.addNewId
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.clear
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.InvokeUtil.createView
import org.appdevforall.codeonthego.layouteditor.utils.InvokeUtil.invokeMethod
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

class XmlLayoutParser(context: Context) {

    val viewAttributeMap: HashMap<View, AttributeMap> = HashMap()

    private val initializer: AttributeInitializer
    private val listViews: MutableList<View> = ArrayList()

    init {
        val attributes = Gson()
            .fromJson<HashMap<String, List<HashMap<String, Any>>>>(
                FileUtil.readFromAsset(Constants.ATTRIBUTES_FILE, context),
                object : TypeToken<HashMap<String, List<HashMap<String, Any>>>>() {}.type
            )
        val parentAttributes = Gson()
            .fromJson<HashMap<String, List<HashMap<String, Any>>>>(
                FileUtil.readFromAsset(Constants.PARENT_ATTRIBUTES_FILE, context),
                object : TypeToken<HashMap<String, List<HashMap<String, Any>>>>() {}.type
            )
        initializer = AttributeInitializer(context, attributes, parentAttributes)
    }

    val root: View?
        get() = listViews.getOrNull(0)

    fun parseFromXml(xml: String, context: Context) {
        listViews.clear()
        viewAttributeMap.clear()
        clear()

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            parseFromXml(parser, context)
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        for ((view, map) in viewAttributeMap) {
            if (map.contains("android:id")) {
                addNewId(view, map.getValue("android:id"))
            }
            applyAttributes(view, map)
        }
    }

    private fun parseFromXml(parser: XmlPullParser, context: Context) {
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tagName = parser.name

                    // Skip NavigationView to avoid invalid parent crash
                    if (tagName == "com.google.android.material.navigation.NavigationView") {
                        Log.w(
                            "XmlParser",
                            "Skipping NavigationView tag to avoid drawer hierarchy crash"
                        )
                        parser.next()
                        continue
                    }

                    var view: View? = null

                    when (tagName) {
                        "fragment" -> {
                            view = FrameLayout(context).apply {
                                id = View.generateViewId()
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                            listViews.add(view)
                        }

                        "include" -> {
                            val layoutAttr = parser.getAttributeValue(null, "layout")
                            val layoutName = layoutAttr?.removePrefix("@layout/")

                            if (!layoutName.isNullOrBlank()) {
                                val resId = context.resources.getIdentifier(
                                    layoutName,
                                    "layout",
                                    context.packageName
                                )
                                if (resId != 0) {
                                    val includedParser = context.resources.getLayout(resId)
                                    parseFromXml(includedParser, context)
                                } else {
                                    Log.e("XmlParser", "Unknown included layout: $layoutName")
                                }
                            }
                            parser.next()
                            continue
                        }

                        "merge" -> {
                            Log.d("XmlParser", "Encountered <merge> tag, skipping itself")
                        }

                        else -> {
                            val result = createView(tagName, context)
                            if (result is Exception) throw result
                            else {
                                view = result as? View
                                view?.let { listViews.add(it) }
                            }
                        }
                    }

                    val map = AttributeMap()
                    for (i in 0 until parser.attributeCount) {
                        val fullName = parser.getAttributeName(i)
                        val value = parser.getAttributeValue(i)
                        map.putValue(fullName, value)
                    }
                    view?.let { viewAttributeMap[it] = map }
                }

                /**
                 * This method is responsible for:
                 * 1) Finding ViewGroups.(that's why we are looking for end tag)
                 * 2) Adding view to ViewGroup as a child.(viewGroup.addView)
                 * 3) Removing the view that was added to it's parent from the list. As it is now stored in the parent,
                 * and we do not need it in the list anymore.
                 * END_TAG event is triggered when we reach the end of each ViewGroup. Top to bottom. Root ViewGroup is
                 * triggered last.
                 *
                 * * Min XML depth for this scenario is 2. File = 0 -> ViewGroup = 1 -> View = 2
                 *
                 * Therefore we are not interested in anything with depth < 2.
                 *
                 * Let's assume depth is 3. File -> LinearLayout -> ConstraintLayout -> View
                 * depth - 2 = 1. This will bring us to the correct parent.
                 * depth - 1 = 1. This will bring us to correct child.
                 *
                 * After adding View to ConstraintLayout, View is removed from the listViews and is considered finished.
                 * This process will repeat until all Views and ViewGroups will be added to corresponding parents and list
                 * view will become empty.
                 */

                XmlPullParser.END_TAG -> {
                    val depth = parser.depth
                    if (depth >= 2 && listViews.size >= 2) {
                        val parent = listViews.getOrNull(depth - 2) ?: return
                        val child = listViews.getOrNull(depth - 1) ?: return
                        if (parent is ViewGroup) {
                            parent.addView(child)
                            listViews.removeAt(depth - 1)
                        }
                    }
                }
            }
            parser.next()
        }

        root?.let {
            restorePositionsAfterLoad(it, viewAttributeMap)
        }
    }

    private fun applyAttributes(target: View, attributeMap: AttributeMap) {
        val allAttrs = initializer.getAllAttributesForView(target)

        val keys = attributeMap.keySet()

        for (i in keys.indices.reversed()) {
            val key = keys[i]

            if (key == "android:id") {
                continue
            }

            val attr = initializer.getAttributeFromKey(key, allAttrs)
            if (attr == null) {
                Log.w(
                    "XmlParser",
                    "Could not find attribute $key for view ${target.javaClass.simpleName}"
                )
                continue
            }

            val methodName = attr[Constants.KEY_METHOD_NAME].toString()
            val className = attr[Constants.KEY_CLASS_NAME].toString()
            val value = attributeMap.getValue(key)

            Log.d("applyAttributes", "Applying attribute $key to view $target with value $value")
            invokeMethod(methodName, className, target, value, target.context)
        }
    }
}