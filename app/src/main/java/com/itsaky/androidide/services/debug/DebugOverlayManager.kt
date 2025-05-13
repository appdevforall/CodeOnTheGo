package com.itsaky.androidide.services.debug

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.DebuggerActionsWindowBinding
import com.itsaky.androidide.editor.databinding.LayoutPopupMenuItemBinding
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Manages interaction with the actions shown in the debugger overlay window.
 *
 * @author Akash Yadav
 */
class DebugOverlayManager private constructor(
    private val windowManager: WindowManager,
    private val binding: DebuggerActionsWindowBinding,
    private val touchSlop: Int = ViewConfiguration.get(binding.root.context).scaledTouchSlop,
) {
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    init {
        // why not use android:clipToOutline in XML?
        // because android:clipToOutline attr is only used on API >= 31
        binding.actions.clipToOutline = true
        binding.dragHandle.root.icon = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_drag_handle)

        binding.dragHandle.root.setOnTouchListener { v, event ->
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWindowX = overlayLayoutParams.x
                    initialWindowY = overlayLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (deltaX > touchSlop || deltaY > touchSlop) {
                        overlayLayoutParams.x = initialWindowX + deltaX
                        overlayLayoutParams.y = initialWindowY + deltaY
                        windowManager.updateViewLayout(binding.root, overlayLayoutParams)
                        true
                    } else false
                }

                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX < touchSlop && deltaY < touchSlop) {
                        v.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private var isShown = false
    private val overlayLayoutParams by lazy {
        WindowManager.LayoutParams(
            /* w = */ WindowManager.LayoutParams.WRAP_CONTENT,
            /* h = */ WindowManager.LayoutParams.WRAP_CONTENT,
            /* _type = */ WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            /* _flags = */ WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            /* _format = */ PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
    }

    fun show() {
        if (isShown) {
            return
        }

        windowManager.addView(binding.root, overlayLayoutParams)
        isShown = true
    }

    fun hide() {
        if (!isShown) {
            return
        }

        windowManager.removeView(binding.root)
        isShown = false
    }

    companion object {

        private val logger = LoggerFactory.getLogger(DebugOverlayManager::class.java)

        /**
         * Create a new [DebugOverlayManager] from the given [Context].
         *
         * @param ctx The [Context] to use for creating the [DebugOverlayManager].
         * @return A new [DebugOverlayManager].
         */
        fun create(ctx: Context): DebugOverlayManager {
            // IMPORTANT!
            // Wrap the context with a theme, so we could use MaterialButtons!
            val context = ContextThemeWrapper(ctx, R.style.Theme_AndroidIDE)
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(context)

            // noinspection InflateParams
            val layout = DebuggerActionsWindowBinding.inflate(inflater)

            val actions = listOf(
                SimpleAction(
                    label = "Pause",
                    icon = R.drawable.ic_run_outline
                ),
                SimpleAction(
                    label = "Step over",
                    icon = R.drawable.ic_run_outline
                ),
                SimpleAction(
                    label = "Step into",
                    icon = R.drawable.ic_run_outline
                ),
                SimpleAction(
                    label = "Step out",
                    icon = R.drawable.ic_run_outline
                ),
                SimpleAction(
                    label = "Restart",
                    icon = R.drawable.ic_run_outline
                ),
                SimpleAction(
                    label = "Stop",
                    icon = R.drawable.ic_run_outline
                ),
            )

            layout.actions.adapter = ActionsAdapter(actions)

            return DebugOverlayManager(
                windowManager,
                layout,
            )
        }
    }

    data class SimpleAction(
        val label: String,

        @DrawableRes
        val icon: Int,
    )

    class ActionsAdapter(
        private val actions: List<SimpleAction>
    ) : RecyclerView.Adapter<ActionsAdapter.VH>() {

        class VH(
            val binding: LayoutPopupMenuItemBinding
        ) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = LayoutPopupMenuItemBinding.inflate(layoutInflater, parent, false)
            return VH(binding)
        }

        override fun getItemCount(): Int {
            return actions.size
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val binding = holder.binding
            val action = actions[position]
            binding.root.icon = ContextCompat.getDrawable(binding.root.context, action.icon)
            TooltipCompat.setTooltipText(binding.root, action.label)
        }
    }
}