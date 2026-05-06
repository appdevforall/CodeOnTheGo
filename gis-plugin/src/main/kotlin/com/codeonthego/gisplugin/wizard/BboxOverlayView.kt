package com.codeonthego.gisplugin.wizard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.codeonthego.gisplugin.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Direct-manipulation bounding-box overlay (Gaia GPS style, per ADFA-2436 §5.2
 * and the wizard step-2 mockup at
 * `docs/mockups/ADFA-2436-maps-mockup.html` lines 644-707).
 *
 * Renders a translucent rectangle on top of whatever the parent view group
 * supplies as a "map". Four 48 dp corner-hit-zones (Material / iOS HIG touch
 * target standard) let the user resize; dragging the interior translates.
 *
 * Why a custom View rather than 4 Views with `OnTouchListener`s:
 *  - the corner-hit zones overlap the rectangle's edges and need different
 *    cursors / hover hints. A single View knows its full geometry.
 *  - we want pointer-down/move/up to atomically own the gesture once started.
 *
 * Coordinates: pixel space (x, y). Mapping back to lon/lat happens in
 * [WizardActivity] which knows the zoom level + map centre. Storing the bbox
 * in pixel space here lets the picker stay decoupled from the map projection
 * for the C2 stub (which uses a flat SVG-style background, not real tiles).
 */
class BboxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Listener fires every time the bbox is mutated by user input. */
    fun interface Listener {
        fun onBboxChanged(rect: RectF)
    }

    private val rect = RectF()
    private val handleHitRadius = dp(24f)   // 48 dp diameter
    private val handleVisibleRadius = dp(10f) // 20 dp diameter
    private val borderWidth = dp(1.5f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(46, 80, 80, 80)  // 18 % opacity grey, matches mockup line 699
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = Color.argb(153, 0, 0, 0) // ~60 % opacity black
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Lazy-initialise from theme on first draw; default is sensible green.
        color = Color.parseColor("#16A34A")
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }

    private var listener: Listener? = null
    private var dragMode: DragMode = DragMode.NONE
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var dragStartRect: RectF = RectF()
    private val minSidePx = dp(48f)

    init {
        // Theme override: pick up the plugin's primary colour for the handles.
        runCatching {
            handleFillPaint.color = ContextCompat.getColor(context, R.color.plugin_primary)
        }
    }

    /** Replace the bbox with a new pixel-space rectangle. Triggers redraw. */
    fun setBboxPx(left: Float, top: Float, right: Float, bottom: Float) {
        rect.set(left, top, right, bottom)
        listener?.onBboxChanged(RectF(rect))
        invalidate()
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun currentBboxPx(): RectF = RectF(rect)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (rect.isEmpty && w > 0 && h > 0) {
            // Default: 60 %-edged square centred in the view. Activity will
            // override this once it knows the GPS-derived default.
            val side = min(w, h) * 0.6f
            val cx = w / 2f
            val cy = h / 2f
            setBboxPx(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) return
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, borderPaint)
        // Corner handles
        val corners = floatArrayOf(
            rect.left, rect.top,
            rect.right, rect.top,
            rect.left, rect.bottom,
            rect.right, rect.bottom
        )
        var i = 0
        while (i < corners.size) {
            val cx = corners[i]
            val cy = corners[i + 1]
            canvas.drawCircle(cx, cy, handleVisibleRadius, handleFillPaint)
            canvas.drawCircle(cx, cy, handleVisibleRadius, handleStrokePaint)
            i += 2
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val mode = hitTest(event.x, event.y)
                if (mode == DragMode.NONE) return false
                dragMode = mode
                dragStartX = event.x
                dragStartY = event.y
                dragStartRect.set(rect)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                applyDrag(dx, dy)
                listener?.onBboxChanged(RectF(rect))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) {
                    dragMode = DragMode.NONE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Resolve a pointer-down location to an interaction mode. Order matters:
     * corner hit-test first (large 48 dp targets) so a tap near a corner
     * always resizes rather than translating.
     */
    private fun hitTest(x: Float, y: Float): DragMode {
        if (rect.isEmpty) return DragMode.NONE
        val tlDist = distance(x, y, rect.left, rect.top)
        val trDist = distance(x, y, rect.right, rect.top)
        val blDist = distance(x, y, rect.left, rect.bottom)
        val brDist = distance(x, y, rect.right, rect.bottom)
        val nearest = minOf(tlDist, trDist, blDist, brDist)
        if (nearest <= handleHitRadius) {
            return when (nearest) {
                tlDist -> DragMode.RESIZE_TL
                trDist -> DragMode.RESIZE_TR
                blDist -> DragMode.RESIZE_BL
                brDist -> DragMode.RESIZE_BR
                else -> DragMode.NONE
            }
        }
        if (rect.contains(x, y)) return DragMode.MOVE
        return DragMode.NONE
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val s = dragStartRect
        val parentW = (width - 1).toFloat().coerceAtLeast(0f)
        val parentH = (height - 1).toFloat().coerceAtLeast(0f)
        when (dragMode) {
            DragMode.MOVE -> {
                val targetLeft = (s.left + dx).coerceIn(0f, max(0f, parentW - s.width()))
                val targetTop = (s.top + dy).coerceIn(0f, max(0f, parentH - s.height()))
                rect.set(targetLeft, targetTop, targetLeft + s.width(), targetTop + s.height())
            }
            DragMode.RESIZE_TL -> {
                val left = (s.left + dx).coerceIn(0f, s.right - minSidePx)
                val top = (s.top + dy).coerceIn(0f, s.bottom - minSidePx)
                rect.set(left, top, s.right, s.bottom)
            }
            DragMode.RESIZE_TR -> {
                val right = (s.right + dx).coerceIn(s.left + minSidePx, parentW)
                val top = (s.top + dy).coerceIn(0f, s.bottom - minSidePx)
                rect.set(s.left, top, right, s.bottom)
            }
            DragMode.RESIZE_BL -> {
                val left = (s.left + dx).coerceIn(0f, s.right - minSidePx)
                val bottom = (s.bottom + dy).coerceIn(s.top + minSidePx, parentH)
                rect.set(left, s.top, s.right, bottom)
            }
            DragMode.RESIZE_BR -> {
                val right = (s.right + dx).coerceIn(s.left + minSidePx, parentW)
                val bottom = (s.bottom + dy).coerceIn(s.top + minSidePx, parentH)
                rect.set(s.left, s.top, right, bottom)
            }
            DragMode.NONE -> Unit
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))

    private enum class DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
}
