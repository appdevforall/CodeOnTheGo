package com.itsvks.layouteditor.tools

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.LinearLayoutCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsvks.layouteditor.editor.initializer.AttributeInitializer
import com.itsvks.layouteditor.editor.initializer.AttributeMap
import com.itsvks.layouteditor.managers.IdManager.addNewId
import com.itsvks.layouteditor.managers.IdManager.clear
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.FileUtil
import com.itsvks.layouteditor.utils.InvokeUtil.createView
import com.itsvks.layouteditor.utils.InvokeUtil.invokeMethod
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
                object : TypeToken<HashMap<String, List<HashMap<String, Any>>>>() {
                }.type
            )
        val parentAttributes = Gson()
            .fromJson<HashMap<String, List<HashMap<String, Any>>>>(
                FileUtil.readFromAsset(Constants.PARENT_ATTRIBUTES_FILE, context),
                object : TypeToken<HashMap<String, List<HashMap<String, Any>>>>() {
                }.type
            )

        initializer = AttributeInitializer(context, attributes, parentAttributes)
    }

    val root: View
        get() {
            return listViews[0]
        }

    fun parseFromXml(xml: String, context: Context) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {

                    /**
                     * This method finds all the views goth ViewGroups and Views.
                     * And adds them to the listview.
                     */
                    XmlPullParser.START_TAG -> {
                        val view = createView(parser.name, context) as View
                        listViews.add(view)

                        val map = AttributeMap()

                        var i = 0
                        while (i < parser.attributeCount) {
                            if (!parser.getAttributeName(i).startsWith("xmlns")) {
                                map.putValue(parser.getAttributeName(i), parser.getAttributeValue(i))
                            }
                            i++
                        }

                        viewAttributeMap[view] = map
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
                        if (depth >= 2) {
                            (listViews[depth - 2] as ViewGroup).addView(listViews[depth - 1])
                            listViews.removeAt(depth - 1)
                        }
                    }
                }
                parser.next()
            }
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        clear()

        for (view in viewAttributeMap.keys) {
            val map = viewAttributeMap[view]!!

            for (key in map.keySet()) {
                if (key == "android:id") {
                    addNewId(view, map.getValue("android:id"))
                }
            }
        }

        for (view in viewAttributeMap.keys) {
            val map = viewAttributeMap[view]!!
            applyAttributes(view, map)
        }
    }

    private fun applyAttributes(target: View, attributeMap: AttributeMap) {
        val allAttrs = initializer.getAllAttributesForView(target)

        val keys = attributeMap.keySet()

        for (i in keys.indices.reversed()) {
            val key = keys[i]

            val attr = initializer.getAttributeFromKey(key, allAttrs) ?: return
            val methodName = attr[Constants.KEY_METHOD_NAME].toString()
            val className = attr[Constants.KEY_CLASS_NAME].toString()
            val value = attributeMap.getValue(key)

            if (key == "android:id") {
                continue
            }

            invokeMethod(methodName, className, target, value, target.context)
        }
    }
}
