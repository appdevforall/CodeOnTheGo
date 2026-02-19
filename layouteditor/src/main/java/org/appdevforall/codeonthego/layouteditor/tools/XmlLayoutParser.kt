package org.appdevforall.codeonthego.layouteditor.tools

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeInitializer
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap
import org.appdevforall.codeonthego.layouteditor.editor.positioning.restorePositionsAfterLoad
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.addNewId
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.clear
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.Constants.ATTR_INITIAL_POS
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.editor.convert.ConvertImportedXml
import org.appdevforall.codeonthego.layouteditor.utils.InvokeUtil.createView
import org.appdevforall.codeonthego.layouteditor.utils.InvokeUtil.invokeMethod
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.File
import java.io.StringReader
import androidx.core.view.isNotEmpty
import androidx.core.widget.NestedScrollView


sealed class ValidationResult {
	object Success : ValidationResult()
	data class Error(val errors: List<String>) : ValidationResult() {
		val formattedMessage: String get() = errors.joinToString(separator = "\n\n• ", prefix = "• ")
	}
}

class XmlLayoutParser(
	context: Context,
  private val basePath: String? = null,
) {
	val viewAttributeMap: HashMap<View, AttributeMap> = HashMap()
	private val validationErrors = mutableListOf<String>()

	private val initializer: AttributeInitializer
	private val listViews: MutableList<View> = ArrayList()

	companion object {
    const val MARKER_IS_INCLUDE = "tools:is_xml_include"
    const val MARKER_IS_FRAGMENT = "tools:is_xml_fragment"
    const val MARKER_IS_MERGE = "tools:is_xml_merge"
    const val TAG = "XmlLayoutParser"
	}

	enum class CustomAttrs(val key: String) {
		INITIAL_POS(ATTR_INITIAL_POS),
	}

	init {
		val attributes =
			Gson()
				.fromJson<HashMap<String, List<HashMap<String, Any>>>>(
					FileUtil.readFromAsset(Constants.ATTRIBUTES_FILE, context),
					object : TypeToken<HashMap<String, List<HashMap<String, Any>>>>() {}.type,
				)
		val parentAttributes =
			Gson()
				.fromJson<HashMap<String, List<HashMap<String, Any>>>>(
					FileUtil.readFromAsset(Constants.PARENT_ATTRIBUTES_FILE, context),
					object : TypeToken<HashMap<String, List<HashMap<String, Any>>>>() {}.type,
				)
		initializer = AttributeInitializer(context, attributes, parentAttributes)
	}

	val root: View?
		get() = listViews.getOrNull(0)

	fun validateXml(
		xml: String,
		context: Context,
	): ValidationResult {
		listViews.clear()
		viewAttributeMap.clear()
		validationErrors.clear()
		clear()

		return try {
			val factory = XmlPullParserFactory.newInstance()
			val parser = factory.newPullParser()
			parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
			parser.setInput(StringReader(xml))
			parseFromXml(parser, context)

			if (validationErrors.isEmpty()) {
				ValidationResult.Success
			} else {
				ValidationResult.Error(validationErrors.toList())
			}
		} catch (e: XmlPullParserException) {
			ValidationResult.Error(listOf(context.getString(R.string.xml_error_parse, e.message ?: "")))
		} catch (e: IOException) {
			ValidationResult.Error(listOf(context.getString(R.string.xml_error_io, e.message ?: "")))
		} catch (e: Exception) {
			ValidationResult.Error(listOf(context.getString(R.string.xml_error_generic, e.message ?: "")))
		}
	}

	fun parseFromXml() {
		for ((view, map) in viewAttributeMap) {
			if (map.contains("android:id")) {
				addNewId(view, map.getValue("android:id"))
			}
			applyAttributes(view, map)
		}
	}

	fun processXml(xml: String, context: Context): ValidationResult {
		val result = validateXml(xml, context)

		if (result is ValidationResult.Success) {
			parseFromXml()
		} else if (result is ValidationResult.Error) {
			Log.e(TAG, "Failed to parse layout. Errors:\n${result.formattedMessage}")
		}

		return result
	}

	private fun parseFromXml(
		parser: XmlPullParser,
		context: Context,
	) {
		while (parser.eventType != XmlPullParser.END_DOCUMENT) {
			when (parser.eventType) {
				XmlPullParser.START_TAG -> {
					val tagName = parser.name

					// Skip NavigationView to avoid invalid parent crash
					if (tagName == "com.google.android.material.navigation.NavigationView") {
						Log.w(
							TAG,
							"Skipping NavigationView tag to avoid drawer hierarchy crash",
						)
						parser.next()
						continue
					}

					var view: View? = null

					when (tagName) {
						"fragment" -> {
							val placeholder = FrameLayout(context).apply {
								id = View.generateViewId()
								layoutParams = ViewGroup.LayoutParams(
									ViewGroup.LayoutParams.MATCH_PARENT,
									ViewGroup.LayoutParams.MATCH_PARENT,
								)
							}

							val attrs = AttributeMap()
							for (i in 0 until parser.attributeCount) {
								attrs.putValue(parser.getAttributeName(i), parser.getAttributeValue(i))
							}
							attrs.putValue(MARKER_IS_FRAGMENT, "true")

							viewAttributeMap[placeholder] = attrs
							listViews.add(placeholder)

							parser.next()
							continue
						}

                        "include" -> {
                            val layoutAttr =
                                XmlParserUtils.getAttribute(parser, "layout")

                            val includedView = loadIncludedLayout(
                                context,
                                basePath,
                                layoutAttr
                            )

                            val view =
                                includedView ?: XmlParserUtils.createIncludePlaceholder(
                                    context,
                                    viewAttributeMap,
                                    MARKER_IS_INCLUDE
                                )

                            listViews.add(view)

                            XmlParserUtils.applyAttributes(
                                parser = parser,
                                target = view,
                                attributeMap = viewAttributeMap,
                                marker = MARKER_IS_INCLUDE,
                                skip = if (includedView != null) null else "layout"
                            )

                            parser.next()
                            continue
                        }

                        "merge" -> {

                            val wrapper =
                                XmlParserUtils.createMergeWrapper(context)

                            applyMergeAttributes(
                                parser = parser,
                                target = wrapper,
                                attributeMap = viewAttributeMap,
                                marker = MARKER_IS_MERGE
                            )

                            listViews.add(wrapper)
                        }

                        else -> {
							val result = createView(tagName, context)
							if (result is Exception) {
								throw result
							} else {
								view = result as? View
								view?.let { listViews.add(it) }
							}
						}
					}

          if (view != null) {
						val map = AttributeMap()
						for (i in 0 until parser.attributeCount) {
							val fullName = parser.getAttributeName(i)
							val value = parser.getAttributeValue(i)
							map.putValue(fullName, value)
						}
						viewAttributeMap[view] = map
					}
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

				XmlPullParser.END_TAG -> run {
					val depth = parser.depth
					if (depth < 2 || listViews.size < 2) return@run

					val parent = listViews.getOrNull(depth - 2) as? ViewGroup ?: return@run
					val child = listViews.getOrNull(depth - 1) ?: return@run

					parent.tryAddChild(child)
					listViews.removeAt(depth - 1)
				}
			}
			parser.next()
		}

		root?.let {
			restorePositionsAfterLoad(it, viewAttributeMap)
		}
	}

	private fun ViewGroup.tryAddChild(child: View) {
		if (isSingleChildContainer() && isNotEmpty()) {
			val errorMsg = context.getString(
				R.string.xml_error_single_child_container,
				this::class.simpleName
			)
			Log.w(TAG, errorMsg)
			validationErrors.add(errorMsg)
			return
		}

		runCatching {
			addView(child)
		}.onFailure { e ->
			val errorMsg = context.getString(
				R.string.xml_error_add_child_failed,
				child::class.simpleName,
				this::class.simpleName
			)
			Log.e(TAG, errorMsg, e)
			validationErrors.add(errorMsg)
		}
	}

	private fun ViewGroup.isSingleChildContainer(): Boolean {
		return this is ScrollView || this is HorizontalScrollView || this is NestedScrollView
	}

	private fun applyInitialPosition(target: View, attrs: AttributeMap) {
		if (attrs.contains("android:layout_marginStart")) return

		val initialPosition = attrs.getValue(ATTR_INITIAL_POS)
		if (shouldCenter(initialPosition, target)) {
			applyBaseCenterConstraints(target, attrs)
			centerAfterLayout(target, attrs)
		}
	}

	private fun applyCustomAttributes(
    target: View,
    attributeMap: AttributeMap
	) {
		CustomAttrs.entries.forEach { attr ->
			if (!attributeMap.contains(attr.key)) return@forEach

			when (attr) {
				CustomAttrs.INITIAL_POS ->
					applyInitialPosition(target, attributeMap)
			}
		}
	}

	private fun applyAttributes(
		target: View,
		attributeMap: AttributeMap,
	) {
		val allAttrs = initializer.getAllAttributesForView(target)

		val keys = attributeMap.keySet()

		applyCustomAttributes(target, attributeMap)

		for (i in keys.indices.reversed()) {
			val key = keys[i]

			if (key == "android:id") {
				continue
			}

			val attr = initializer.getAttributeFromKey(key, allAttrs)
			if (attr == null) {
				Log.w(
					TAG,
					"Could not find attribute $key for view ${target.javaClass.simpleName}",
				)
				continue
			}

			val methodName = attr[Constants.KEY_METHOD_NAME].toString()
			val className = attr[Constants.KEY_CLASS_NAME].toString()
			val value = attributeMap.getValue(key)

			Log.d(TAG, "Applying attribute $key to view $target with value $value")
			invokeMethod(methodName, className, target, value, target.context)
		}
	}

	private fun shouldCenter(initialPosition: String?, target: View): Boolean {
		return initialPosition == "center" && target.parent is ConstraintLayout
	}

	private fun applyBaseCenterConstraints(
	  target: View,
	  attributeMap: AttributeMap
	) {
		val params = (target.layoutParams as? ConstraintLayout.LayoutParams) ?: return

		params.apply {
			startToStart = ConstraintLayout.LayoutParams.PARENT_ID
			topToTop = ConstraintLayout.LayoutParams.PARENT_ID
			endToEnd = ConstraintLayout.LayoutParams.UNSET
			bottomToBottom = ConstraintLayout.LayoutParams.UNSET
			setMargins(0, 0, 0, 0)
		}
		target.layoutParams = params

		attributeMap.apply {
			putValue("app:layout_constraintStart_toStartOf", "parent")
			putValue("app:layout_constraintTop_toTopOf", "parent")

			removeValue("app:layout_constraintEnd_toEndOf")
			removeValue("app:layout_constraintBottom_toBottomOf")
			removeValue("app:layout_constraintHorizontal_bias")
			removeValue("app:layout_constraintVertical_bias")
		}
	}

	private fun centerAfterLayout(
	  target: View,
	  attributeMap: AttributeMap
	) {
		target.post {
			val parent = target.parent as View
			if (parent.width <= 0 || parent.height <= 0) return@post

			val centeredX = (parent.width - target.width) / 2
			val centeredY = (parent.height - target.height) / 2

			val layoutParams = (target.layoutParams as? ConstraintLayout.LayoutParams) ?: return@post

			layoutParams.apply {
				marginStart = centeredX
				topMargin = centeredY
			}.also { target.layoutParams = it }

			val density = target.resources.displayMetrics.density
			val startDp = (centeredX / density).toInt()
			val topDp = (centeredY / density).toInt()

			attributeMap.putValue("android:layout_marginStart", "${startDp}dp")
			attributeMap.putValue("android:layout_marginTop", "${topDp}dp")
		}
	}

    fun loadIncludedLayout(
        context: Context,
        basePath: String?,
        layoutAttr: String?
    ): View? {
        if (layoutAttr == null || basePath == null) {
            Log.w(
                TAG,
                "Skipping include. layoutAttr=$layoutAttr basePath=$basePath"
            )
            return null
        }

        val layoutName = layoutAttr.substringAfterLast("/")
        val file = File(basePath, "$layoutName.xml")

        if (!file.exists()) {
            Log.e(
                TAG,
                "Included file not found: ${file.absolutePath}"
            )
            return null
        }

        return try {
            val xml = file.readText()

            val converted =
                ConvertImportedXml(xml)
                    .getXmlConverted(context)
                    ?: xml

            val parser = XmlLayoutParser(context, basePath)

            val result = parser.processXml(converted, context)
						if (result is ValidationResult.Error) {
						    Log.e(TAG, "Included layout has errors: ${result.formattedMessage}")
						    return null
						}

            parser.root
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to parse include: $layoutName",
                e
            )
            null
        }
    }

    fun applyMergeAttributes(
        parser: XmlPullParser,
        target: View,
        attributeMap: MutableMap<View, AttributeMap>,
        marker: String
    ) {
        val map = XmlParserUtils.extractAttributes(parser)

        map.putValue(marker, "true")
        map.putValue("android:layout_width", "match_parent")
        map.putValue("android:layout_height", "wrap_content")

        attributeMap[target] = map
    }
}
