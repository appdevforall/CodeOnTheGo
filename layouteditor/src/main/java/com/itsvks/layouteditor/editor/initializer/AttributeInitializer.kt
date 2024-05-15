package com.itsvks.layouteditor.editor.initializer

import android.content.Context
import android.view.View
import com.itsvks.layouteditor.editor.DesignEditor
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.InvokeUtil.invokeMethod

class AttributeInitializer {
  private var context: Context

  private var viewAttributeMap = HashMap<View, AttributeMap>()
  private var attributes: HashMap<String, List<HashMap<String, Any>>>
  private var parentAttributes: HashMap<String, List<HashMap<String, Any>>>

  constructor(
    context: Context,
    attributes: HashMap<String, List<HashMap<String, Any>>>,
    parentAttributes: HashMap<String, List<HashMap<String, Any>>>
  ) {
    this.context = context
    this.attributes = attributes
    this.parentAttributes = parentAttributes
  }

  constructor(
    context: Context,
    viewAttributeMap: HashMap<View, AttributeMap>,
    attributes: HashMap<String, List<HashMap<String, Any>>>,
    parentAttributes: HashMap<String, List<HashMap<String, Any>>>
  ) {
    this.viewAttributeMap = viewAttributeMap
    this.context = context
    this.attributes = attributes
    this.parentAttributes = parentAttributes
  }

  fun applyDefaultAttributes(target: View, defaultAttrs: Map<String, String?>) {
    val allAttrs = getAllAttributesForView(target)

    for (key in defaultAttrs.keys) {
      for (map in allAttrs) {
        if (map[Constants.KEY_ATTRIBUTE_NAME].toString() == key) {
          applyAttribute(target, defaultAttrs[key]!!, map)
          break
        }
      }
    }
  }

  fun applyAttribute(
    target: View, value: String, attribute: HashMap<String, Any>
  ) {
    val methodName = attribute?.get(Constants.KEY_METHOD_NAME)?.toString()
    val className = attribute?.get(Constants.KEY_CLASS_NAME)?.toString()
    val attributeName = attribute?.get(Constants.KEY_ATTRIBUTE_NAME)?.toString()

    // update ids attributes for all views
    if (value.startsWith("@+id/") && viewAttributeMap[target]!!.contains("android:id")) {
      for (view in viewAttributeMap.keys) {
        val map = viewAttributeMap[view]

        for (key in map!!.keySet()) {
          val `val` = map.getValue(key)

          if (`val`.startsWith("@id/") && `val` == viewAttributeMap[target]!!
              .getValue("android:id").replace("+", "")
          ) {
            map.putValue(key, value.replace("+", ""))
          }
        }
      }
    }

    if (attributeName != null) {
      viewAttributeMap[target]!!.putValue(attributeName, value)
    }
    if (methodName != null) {
      if (className != null) {
        invokeMethod(methodName, className, target, value, context)
      }
    }
  }

  fun getAvailableAttributesForView(target: View): List<HashMap<String, Any>> {
    val keys = viewAttributeMap[target]!!.keySet()
    val allAttrs = getAllAttributesForView(target)

    for (i in allAttrs.indices.reversed()) {
      for (key in keys) {
        if (key == allAttrs[i][Constants.KEY_ATTRIBUTE_NAME].toString()) {
          allAttrs.removeAt(i)
          break
        }
      }
    }

    return allAttrs
  }

  @Suppress("UNCHECKED_CAST")
  fun getAllAttributesForView(target: View): MutableList<HashMap<String, Any>> {
    val allAttrs: MutableList<HashMap<String, Any>> = ArrayList()

    var cls = target.javaClass
    val viewParentCls = View::class.java.superclass

    while (cls != viewParentCls) {
      if (attributes.containsKey(cls.name)) allAttrs.addAll(0, attributes[cls.name]!!)

      cls = cls.superclass as Class<View>
    }

    if (target.parent != null && target.parent.javaClass != DesignEditor::class.java) {
      cls = target.parent.javaClass as Class<View>

      while (cls != viewParentCls) {
        if (parentAttributes.containsKey(cls.name)) allAttrs.addAll(parentAttributes[cls.name]!!)

        cls = cls.superclass as Class<View>
      }
    }

    return allAttrs
  }

  fun getAttributeFromKey(
    key: String, list: MutableList<HashMap<String, Any>>
  ): HashMap<String, Any>? {
    for (map in list) {
      if (map[Constants.KEY_ATTRIBUTE_NAME] == key) return map
    }
    return null
  }
}
