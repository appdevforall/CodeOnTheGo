package org.appdevforall.codeonthego.layouteditor.editor

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.DragEvent
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isEmpty
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.VibrateUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.adapters.AppliedAttributesAdapter
import org.appdevforall.codeonthego.layouteditor.databinding.ShowAttributesDialogBinding
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.AttributeDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.BooleanDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.ColorDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.DimensionDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.EnumDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.FlagDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.IdDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.NumberDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.SizeDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.StringDialog
import org.appdevforall.codeonthego.layouteditor.editor.dialogs.ViewDialog
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeInitializer
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap
import org.appdevforall.codeonthego.layouteditor.managers.IdManager
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.addId
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.getViewId
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.removeId
import org.appdevforall.codeonthego.layouteditor.managers.PreferencesManager
import org.appdevforall.codeonthego.layouteditor.managers.UndoRedoManager
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutGenerator
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutParser
import org.appdevforall.codeonthego.layouteditor.utils.ArgumentUtil.parseType
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.InvokeUtil
import org.appdevforall.codeonthego.layouteditor.utils.Utils
import org.appdevforall.codeonthego.layouteditor.views.StructureView
import kotlin.math.abs

class DesignEditor : LinearLayout {
    var viewType: ViewType? = null
        set(value) {
            isBlueprint = viewType == ViewType.BLUEPRINT
            setBlueprintOnChildren()
            invalidate()
            field = value
        }
    var deviceConfiguration: DeviceConfiguration? = null
    var apiLevel: APILevel? = null

    lateinit var viewAttributeMap: HashMap<View, AttributeMap>
        private set

    private lateinit var paint: Paint
    private lateinit var shadow: View

    private lateinit var attributes: HashMap<String, List<HashMap<String, Any>>>
    private lateinit var parentAttributes: HashMap<String, List<HashMap<String, Any>>>
    private lateinit var initializer: AttributeInitializer

    private var isBlueprint = false
    private var structureView: StructureView? = null
    private var undoRedoManager: UndoRedoManager? = null
    private var isModified = false
    private lateinit var preferencesManager: PreferencesManager
    private var parser: XmlLayoutParser? = null

    init {
        initAttributes()
    }

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(context)
    }

    fun getParser(): XmlLayoutParser? {
        return parser
    }
    private fun init(context: Context) {
        viewType = ViewType.DESIGN
        isBlueprint = false
        deviceConfiguration = DeviceConfiguration(DeviceSize.LARGE)
        //initAttributes()
        shadow = View(context)
        paint = Paint()

        this.preferencesManager = PreferencesManager(context)

        shadow.setBackgroundColor(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
        )
        shadow.layoutParams = ViewGroup.LayoutParams(
            Utils.pxToDp(context, 50),
            Utils.pxToDp(context, 35)
        )
        paint.strokeWidth = Utils.pxToDp(context, 3).toFloat()

        orientation = VERTICAL
        setTransition(this)
        setDragListener(this)

        toggleStrokeWidgets()
        setBlueprintOnChildren()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        when (viewType) {
            ViewType.BLUEPRINT -> drawBlueprint(canvas)
            ViewType.DESIGN -> drawDesign(canvas)
            else -> drawDesign(canvas)
        }
        when (deviceConfiguration!!.size) {
            DeviceSize.SMALL -> {
                scaleX = 0.75f
                scaleY = 0.75f
            }

            DeviceSize.MEDIUM -> {
                scaleX = 0.85f
                scaleY = 0.85f
            }

            DeviceSize.LARGE -> {
                scaleX = 0.95f
                scaleY = 0.95f
            }
        }
    }

    private fun drawBlueprint(canvas: Canvas) {
        paint.color = Constants.BLUEPRINT_DASH_COLOR
        setBackgroundColor(Constants.BLUEPRINT_BACKGROUND_COLOR)
        Utils.drawDashPathStroke(this, canvas, (paint))
    }

    private fun drawDesign(canvas: Canvas) {
        paint.color = Constants.DESIGN_DASH_COLOR
        setBackgroundColor(Utils.getSurfaceColor(context))
        Utils.drawDashPathStroke(this, canvas, (paint))
    }

    fun previewLayout(deviceConfiguration: DeviceConfiguration?, apiLevel: APILevel?) {
        this.deviceConfiguration = deviceConfiguration
        this.apiLevel = apiLevel
    }

    fun resizeLayout(deviceConfiguration: DeviceConfiguration?) {
        this.deviceConfiguration = deviceConfiguration
        invalidate()
    }

    private fun setTransition(group: ViewGroup) {
        if (group is RecyclerView) return
        LayoutTransition().apply {
            disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(150)
        }.also { group.layoutTransition = it }
    }

    private fun toggleStrokeWidgets() {
        try {
            for (view in viewAttributeMap.keys) {
                val cls = view.javaClass
                val method = cls.getMethod("setStrokeEnabled", Boolean::class.javaPrimitiveType)
                method.invoke(view, preferencesManager.isShowStroke)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setBlueprintOnChildren() {
        try {
            for (view in viewAttributeMap.keys) {
                val cls = view.javaClass
                val method = cls.getMethod("setBlueprint", Boolean::class.javaPrimitiveType)
                method.invoke(view, isBlueprint)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setDragListener(group: ViewGroup) {
        group.setOnDragListener(
            OnDragListener { host, event ->
                var parent = host as ViewGroup
                val draggedView =
                    if (event.localState is View) event.localState as View else null

                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        if (preferencesManager.isEnableVibration) VibrateUtils.vibrate(100)
                        if ((draggedView != null
                                    && !(draggedView is AdapterView<*> && parent is AdapterView<*>))
                        ) parent.removeView(draggedView)
                    }

                    DragEvent.ACTION_DRAG_EXITED -> {
                        removeWidget(shadow)
                        updateUndoRedoHistory()
                    }

                    DragEvent.ACTION_DRAG_ENDED -> if (!event.result && draggedView != null) {
                        removeId(draggedView, draggedView is ViewGroup)
                        removeViewAttributes(draggedView)
                        viewAttributeMap.remove(draggedView)
                        updateStructure()
                    }

                    DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_ENTERED -> if (shadow.parent == null) addWidget(
                        shadow,
                        parent,
                        event
                    )
                    else {
                        if (parent is LinearLayout) {
                            val index = parent.indexOfChild(shadow)
                            val newIndex = getIndexForNewChildOfLinear(parent, event)

                            if (index != newIndex) {
                                parent.removeView(shadow)
                                try {
                                    parent.addView(shadow, newIndex)
                                } catch (_: IllegalStateException) {
                                }
                            }
                        } else {
                            if (shadow.parent !== parent) addWidget(shadow, parent, event)
                        }
                    }

                    DragEvent.ACTION_DROP -> {
                        removeWidget(shadow)
                        if (childCount >= 1) {
                            if (getChildAt(0) !is ViewGroup) {
                                Toast.makeText(
                                    context,
                                    "Can\'t add more than one widget in the editor.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@OnDragListener true
                            } else {
                                if (parent is DesignEditor) parent = getChildAt(0) as ViewGroup
                            }
                        }
                        if (draggedView == null) {
                            @Suppress("UNCHECKED_CAST") val data: HashMap<String, Any> =
                                event.localState as HashMap<String, Any>
                            val newView =
                                InvokeUtil.createView(
                                    data[Constants.KEY_CLASS_NAME].toString(), context
                                ) as View

                            newView.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            rearrangeListeners(newView)

                            if (newView is ViewGroup) {
                                setDragListener(newView)
                                setTransition(newView)
                            }
                            newView.minimumWidth = Utils.pxToDp(context, 20)
                            newView.minimumHeight = Utils.pxToDp(context, 20)

                            if (newView is EditText) newView.isFocusable = false

                            val map = AttributeMap()
                            val id = getIdForNewView(
                                newView.javaClass.superclass.simpleName
                                    .replace(" ".toRegex(), "_")
                                    .lowercase()
                            )
                            IdManager.addNewId(newView, id)
                            map.putValue("android:id", "@+id/$id")
                            map.putValue("android:layout_width", "wrap_content")
                            map.putValue("android:layout_height", "wrap_content")
                            viewAttributeMap[newView] = map

                            addWidget(newView, parent, event)

                            try {
                                val cls: Class<*> = newView.javaClass
                                val setStrokeEnabled =
                                    cls.getMethod(
                                        "setStrokeEnabled",
                                        Boolean::class.javaPrimitiveType
                                    )
                                val setBlueprint =
                                    cls.getMethod("setBlueprint", Boolean::class.javaPrimitiveType)
                                setStrokeEnabled.invoke(newView, preferencesManager.isShowStroke)
                                setBlueprint.invoke(newView, isBlueprint)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            if (data.containsKey(Constants.KEY_DEFAULT_ATTRS)) {
                                @Suppress("UNCHECKED_CAST")
                                initializer.applyDefaultAttributes(
                                    newView,
                                    data[Constants.KEY_DEFAULT_ATTRS] as MutableMap<String, String>
                                )
                            }
                        } else addWidget(draggedView, parent, event)
                        updateStructure()
                        updateUndoRedoHistory()
                    }
                }
                true
            })
    }

    private fun getIdForNewView(name: String): String {
        var id = name
        var n = 0
        var firstTime = true
        while (IdManager.containsId(id)) {
            n++
            id = if (firstTime) "$name$n" else id.replace(
                id.elementAt(id.lastIndex).toString().toRegex(), n.toString()
            )
            firstTime = false
        }
        return id
    }

    fun loadLayoutFromParser(xml: String) {
        clearAll()
        if (xml.isEmpty()) return

        val parser = XmlLayoutParser(context)
        this.parser = parser

        parser.parseFromXml(xml, context)

        addView(parser.root)
        viewAttributeMap = parser.viewAttributeMap

        for (view in (viewAttributeMap as HashMap<View, *>?)!!.keys) {
            rearrangeListeners(view)

            if (view is ViewGroup) {
                setDragListener(view)
                setTransition(view)
            }
            view.minimumWidth = Utils.pxToDp(context, 20)
            view.minimumHeight = Utils.pxToDp(context, 20)
        }

        ensureConstraintsApplied()

        updateStructure()
        toggleStrokeWidgets()

        initializer =
            AttributeInitializer(context, viewAttributeMap, attributes, parentAttributes)
    }

    private fun ensureConstraintsApplied() {
        if (childCount > 0) {
            val rootView = getChildAt(0)

            // For ConstraintLayout or any ViewGroup that might contain a ConstraintLayout
            if (rootView is ViewGroup) {
                // Set full size for root view
                rootView.layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )

                // Force correct constraints by requesting layout and waiting for next layout pass
                rootView.post {
                    rootView.requestLayout()
                    invalidate()
                }
            }
        }
    }

    fun undo() {
        if (undoRedoManager == null) return
        if (undoRedoManager!!.isUndoEnabled) loadLayoutFromParser(undoRedoManager!!.undo())
    }

    fun redo() {
        if (undoRedoManager == null) return
        if (undoRedoManager!!.isRedoEnabled) loadLayoutFromParser(undoRedoManager!!.redo())
    }

    private fun clearAll() {
        removeAllViews()
        structureView?.clear()
        viewAttributeMap.clear()
    }

    fun setStructureView(view: StructureView?) {
        structureView = view
    }

    fun bindUndoRedoManager(manager: UndoRedoManager?) {
        undoRedoManager = manager
    }

    private fun updateStructure() {
        if (isEmpty()) structureView?.clear()
        else structureView?.setView(getChildAt(0))
    }

    fun updateUndoRedoHistory() {
        if (undoRedoManager == null) return
        val result = XmlLayoutGenerator().generate(this, false)

        undoRedoManager!!.addToHistory(result)
        markAsModified()
    }

    fun markAsModified() {
        isModified = true
    }

    fun markAsSaved() {
        isModified = false
    }

    fun isLayoutModified(): Boolean = isModified

    private fun rearrangeListeners(view: View) {
        val gestureDetector =
            GestureDetector(
                context,
                object : SimpleOnGestureListener() {
                    override fun onLongPress(event: MotionEvent) {
                        view.startDragAndDrop(null, DragShadowBuilder(view), view, 0)
                    }
                })

        view.setOnTouchListener(
            object : OnTouchListener {
                var bClick: Boolean = true
                var startX: Float = 0f
                var startY: Float = 0f
                var endX: Float = 0f
                var endY: Float = 0f
                var diffX: Float = 0f
                var diffY: Float = 0f

                @SuppressLint("ClickableViewAccessibility")
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.x
                            startY = event.y
                            bClick = true
                        }

                        MotionEvent.ACTION_UP -> {
                            endX = event.x
                            endY = event.y
                            diffX = abs((startX - endX).toDouble()).toFloat()
                            diffY = abs((startY - endY).toDouble()).toFloat()

                            if ((diffX <= 5) && (diffY <= 5) && bClick) showDefinedAttributes(v)

                            bClick = false
                        }
                    }
                    gestureDetector.onTouchEvent(event)
                    return true
                }
            })
    }

    private fun addWidget(view: View, newParent: ViewGroup, event: DragEvent) {
        removeWidget(view)
        if (newParent is LinearLayout) {
            val index = getIndexForNewChildOfLinear(newParent, event)
            newParent.addView(view, index)
        } else {
            try {
                newParent.addView(view, newParent.childCount)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeWidget(view: View) {
        (view.parent as ViewGroup?)?.removeView(view)
    }

    private fun getIndexForNewChildOfLinear(layout: LinearLayout, event: DragEvent): Int {
        val orientation = layout.orientation
        if (orientation == HORIZONTAL) {
            var index = 0
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child === shadow) continue
                if (child.right < event.x) index++
            }
            return index
        }
        if (orientation == VERTICAL) {
            var index = 0
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child === shadow) continue
                if (child.bottom < event.y) index++
            }
            return index
        }
        return -1
    }

    fun showDefinedAttributes(target: View) {
        val allKeysAndValues = viewAttributeMap[target] ?: return
        val allAttrs = initializer.getAllAttributesForView(target)

        val displayKeys: MutableList<String> = ArrayList()
        val displayValues: MutableList<String> = ArrayList()
        val displayAttrs: MutableList<HashMap<String, Any>> = ArrayList()

        val originalKeys = allKeysAndValues.keySet()
        val originalValues = allKeysAndValues.values()

        for (i in originalKeys.indices) {
            val key = originalKeys[i]

            val foundAttrDef = allAttrs.find { it[Constants.KEY_ATTRIBUTE_NAME].toString() == key }

            if (foundAttrDef != null) {
                displayKeys.add(key)
                displayValues.add(originalValues[i])
                displayAttrs.add(foundAttrDef)
            }
        }

        val dialog = BottomSheetDialog(context)
        val binding = ShowAttributesDialogBinding.inflate(dialog.layoutInflater)
        dialog.setContentView(binding.root)
        TooltipCompat.setTooltipText(binding.btnAdd, "Add attribute")
        TooltipCompat.setTooltipText(binding.btnDelete, "Delete")

        // Now, we use our new, guaranteed-to-be-in-sync lists.
        val appliedAttributesAdapter = AppliedAttributesAdapter(displayAttrs, displayValues)

        appliedAttributesAdapter.onClick = { position ->
            showAttributeEdit(target, displayKeys[position])
            dialog.dismiss()
        }

        appliedAttributesAdapter.onRemoveButtonClick = { position ->
            dialog.dismiss()
            val view = removeAttribute(target, displayKeys[position])
            showDefinedAttributes(view)
        }

        binding.attributesList.adapter = appliedAttributesAdapter
        binding.attributesList.layoutManager =
            LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        binding.viewName.text = target.javaClass.superclass.simpleName
        binding.viewFullName.text = target.javaClass.superclass.name
        binding.btnAdd.setOnClickListener {
            showAvailableAttributes(target)
            dialog.dismiss()
        }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_view)
                .setMessage(R.string.msg_delete_view)
                .setNegativeButton(
                    R.string.no
                ) { d, _ ->
                    d.dismiss()
                }
                .setPositiveButton(
                    R.string.yes
                ) { _, _ ->
                    removeId(target, target is ViewGroup)
                    removeViewAttributes(target)
                    removeWidget(target)
                    updateStructure()
                    updateUndoRedoHistory()
                    dialog.dismiss()
                }
                .show()
        }

        dialog.show()
    }

    private fun showAvailableAttributes(target: View) {
        val availableAttrs =
            initializer.getAvailableAttributesForView(target)
        val names: MutableList<String> = ArrayList()

        for (attr: HashMap<String, Any> in availableAttrs) {
            names.add(attr["name"].toString())
        }

        val dialog = BottomSheetDialog(context)
        val binding = org.appdevforall.codeonthego.layouteditor.databinding.DialogAvailableAttributesBinding.inflate(dialog.layoutInflater)
        
        dialog.setContentView(binding.root)
        
        val adapter = org.appdevforall.codeonthego.layouteditor.adapters.AvailableAttributesAdapter(names) { attributeName ->
            // Find the attribute by name
            for (attr in availableAttrs) {
                if (attr["name"].toString() == attributeName) {
                    showAttributeEdit(target, attr[Constants.KEY_ATTRIBUTE_NAME].toString())
                    break
                }
            }
            dialog.dismiss()
        }
        
        binding.attributesList.adapter = adapter
        binding.attributesList.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        
        // Set up search functionality
        binding.searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.filter(s?.toString() ?: "")
            }
        })
        
        dialog.show()
    }

    private fun showAttributeEdit(target: View, attributeKey: String) {
        val allAttrs = initializer.getAllAttributesForView(target)
        val currentAttr =
            initializer.getAttributeFromKey(attributeKey, allAttrs)
        val attributeMap = viewAttributeMap[target]

        val argumentTypes =
            currentAttr?.get(Constants.KEY_ARGUMENT_TYPE)?.toString()?.split("\\|".toRegex())
                ?.dropLastWhile { it.isEmpty() }
                ?.toTypedArray()

        if (argumentTypes != null) {
            if (argumentTypes.size > 1) {
                if (attributeMap!!.contains(attributeKey)) {
                    val argumentType =
                        parseType(attributeMap.getValue(attributeKey), argumentTypes)
                    showAttributeEdit(target, attributeKey, argumentType)
                    return
                }
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.select_arg_type)
                    .setAdapter(
                        ArrayAdapter(
                            context, android.R.layout.simple_list_item_1, argumentTypes
                        )
                    ) { _, w ->
                        showAttributeEdit(target, attributeKey, argumentTypes[w])
                    }
                    .show()

                return
            }
        }
        showAttributeEdit(target, attributeKey, argumentTypes?.get(0))
    }

    @Suppress("UNCHECKED_CAST")
    private fun showAttributeEdit(
        target: View, attributeKey: String, argumentType: String?
    ) {
        val allAttrs = initializer.getAllAttributesForView(target)
        val currentAttr =
            initializer.getAttributeFromKey(attributeKey, allAttrs)
        val attributeMap = viewAttributeMap[target]

        var savedValue =
            if (attributeMap!!.contains(attributeKey)) attributeMap.getValue(attributeKey) else ""
        val defaultValue =
            if (currentAttr?.containsKey(Constants.KEY_DEFAULT_VALUE) == true)
                currentAttr[Constants.KEY_DEFAULT_VALUE].toString()
            else null
        val constant =
            if (currentAttr?.containsKey(Constants.KEY_CONSTANT) == true
            ) currentAttr[Constants.KEY_CONSTANT].toString()
            else null

        val context = context

        var dialog: AttributeDialog? = null

        when (argumentType) {
            Constants.ARGUMENT_TYPE_SIZE -> dialog = SizeDialog(context, savedValue)
            Constants.ARGUMENT_TYPE_DIMENSION -> dialog =
                DimensionDialog(context, savedValue, currentAttr?.get("dimensionUnit")?.toString())

            Constants.ARGUMENT_TYPE_ID -> dialog = IdDialog(context, savedValue)
            Constants.ARGUMENT_TYPE_VIEW -> dialog = ViewDialog(context, savedValue, constant)
            Constants.ARGUMENT_TYPE_BOOLEAN -> dialog = BooleanDialog(context, savedValue)
            Constants.ARGUMENT_TYPE_DRAWABLE -> {
                if (savedValue.startsWith("@drawable/")) {
                    savedValue = savedValue.replace("@drawable/", "")
                }
                dialog = StringDialog(context, savedValue, Constants.ARGUMENT_TYPE_DRAWABLE)
            }

            Constants.ARGUMENT_TYPE_STRING -> {
                if (savedValue.startsWith("@string/")) {
                    savedValue = savedValue.replace("@string/", "")
                }
                dialog = StringDialog(context, savedValue, Constants.ARGUMENT_TYPE_STRING)
            }

            Constants.ARGUMENT_TYPE_TEXT -> dialog =
                StringDialog(context, savedValue, Constants.ARGUMENT_TYPE_TEXT)

            Constants.ARGUMENT_TYPE_INT -> dialog =
                NumberDialog(context, savedValue, Constants.ARGUMENT_TYPE_INT)

            Constants.ARGUMENT_TYPE_FLOAT -> dialog =
                NumberDialog(context, savedValue, Constants.ARGUMENT_TYPE_FLOAT)

            Constants.ARGUMENT_TYPE_FLAG -> dialog =
                FlagDialog(context, savedValue, currentAttr?.get("arguments") as ArrayList<String>?)

            Constants.ARGUMENT_TYPE_ENUM -> dialog =
                EnumDialog(context, savedValue, currentAttr?.get("arguments") as ArrayList<String>?)

            Constants.ARGUMENT_TYPE_COLOR -> dialog = ColorDialog(context, savedValue)
        }
        if (dialog == null) return

        dialog.setTitle(currentAttr?.get("name")?.toString())
        dialog.setOnSaveValueListener {
            if (defaultValue != null && (defaultValue == it)) {
                if (attributeMap.contains(attributeKey)) removeAttribute(target, attributeKey)
            } else {
                if (currentAttr != null) {
                    initializer.applyAttribute(target, it!!, currentAttr)
                }
                showDefinedAttributes(target)
                updateUndoRedoHistory()
                updateStructure()
            }
        }

        dialog.show()
    }

    private fun removeViewAttributes(view: View) {
        viewAttributeMap.remove(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                removeViewAttributes(view.getChildAt(i))
            }
        }
    }

    private fun removeAttribute(target: View, attributeKey: String): View {
        @Suppress("NAME_SHADOWING")
        var target = target
        val allAttrs = initializer.getAllAttributesForView(target)
        val currentAttr = initializer.getAttributeFromKey(attributeKey, allAttrs)
        val attributeMap = viewAttributeMap[target]

        if (currentAttr?.containsKey(Constants.KEY_CAN_DELETE) == true) {
            return target
        }

        val name =
            if (attributeMap!!.contains("android:id")) attributeMap.getValue("android:id") else null
        val id = if (name != null) getViewId(name.replace("@+id/", "")) else -1
        attributeMap.removeValue(attributeKey)

        if (attributeKey == "android:id") {
            removeId(target, false)
            target.id = -1
            target.requestLayout()

            for (view: View in viewAttributeMap.keys) {
                val map = viewAttributeMap[view]
                for (key: String in map!!.keySet()) {
                    val value = map.getValue(key)
                    if (value.startsWith("@id/") && (value == name!!.replace("+", ""))) {
                        map.removeValue(key)
                    }
                }
            }
            updateStructure()
            return target
        }

        viewAttributeMap.remove(target)
        val parent = target.parent as ViewGroup
        val indexOfView = parent.indexOfChild(target)
        parent.removeView(target)

        val childs: MutableList<View> = ArrayList()
        if (target is ViewGroup) {
            val group = target
            if (group.childCount > 0) {
                for (i in 0 until group.childCount) {
                    childs.add(group.getChildAt(i))
                }
            }
            group.removeAllViews()
        }

        if (name != null) removeId(target, false)
        target = InvokeUtil.createView(target.javaClass.name, context) as View
        rearrangeListeners(target)

        if (target is ViewGroup) {
            target.minimumWidth = Utils.pxToDp(context, 20)
            target.minimumHeight = Utils.pxToDp(context, 20)
            val group = target
            if (childs.isNotEmpty()) {
                for (i in childs.indices) {
                    group.addView(childs[i])
                }
            }
            setTransition(group)
        }

        parent.addView(target, indexOfView)
        viewAttributeMap[target] = attributeMap

        if (name != null) {
            addId(target, name, id)
            target.requestLayout()
        }

        val currentKeys = attributeMap.keySet()
        val currentValues = attributeMap.values()

        for (i in currentKeys.indices) {
            val key = currentKeys[i]

            if (key == "android:id") {
                continue
            }

            val attrDef = initializer.getAttributeFromKey(key, allAttrs)

            if (attrDef != null) {
                initializer.applyAttribute(target, currentValues[i], attrDef)
            }
        }

        try {
            val cls: Class<*> = target.javaClass
            val method = cls.getMethod("setStrokeEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(target, preferencesManager.isShowStroke)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateStructure()
        updateUndoRedoHistory()
        return target
    }

    private fun initAttributes() {
        attributes = convertJsonToJavaObject(Constants.ATTRIBUTES_FILE)
        parentAttributes = convertJsonToJavaObject(Constants.PARENT_ATTRIBUTES_FILE)
        viewAttributeMap = HashMap()
        initializer =
            AttributeInitializer(context, viewAttributeMap, attributes, parentAttributes)
    }

    private fun convertJsonToJavaObject(filePath: String): HashMap<String, List<HashMap<String, Any>>> {
        return Gson()
            .fromJson(
                FileUtil.readFromAsset(filePath, context),
                object : TypeToken<HashMap<String?, ArrayList<HashMap<String?, Any?>?>?>?>() {}.type
            )
    }

    enum class ViewType {
        DESIGN,
        BLUEPRINT
    }
}
