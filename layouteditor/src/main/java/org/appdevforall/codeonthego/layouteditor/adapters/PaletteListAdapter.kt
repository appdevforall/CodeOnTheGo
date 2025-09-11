package org.appdevforall.codeonthego.layouteditor.adapters

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.DragShadowBuilder
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager.showTooltip
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.databinding.LayoutPaletteItemBinding
import org.appdevforall.codeonthego.layouteditor.utils.InvokeUtil.getMipmapId
import org.appdevforall.codeonthego.layouteditor.utils.InvokeUtil.getSuperClassName

class PaletteListAdapter(private val drawerLayout: DrawerLayout) :
  RecyclerView.Adapter<PaletteListAdapter.ViewHolder>() {
  private lateinit var tab: List<HashMap<String, Any>>

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    return ViewHolder(
      LayoutPaletteItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val widgetItem = tab[position]

    val binding = holder.binding

    binding.icon.setImageResource(getMipmapId(widgetItem["iconName"].toString()))
    binding.name.text = widgetItem["name"].toString()
    binding.className.text = getSuperClassName(widgetItem["className"].toString())

    setupGestureHandling(binding, widgetItem)

    binding
      .root.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun setupGestureHandling(binding: LayoutPaletteItemBinding, widgetItem: HashMap<String, Any>) {
    val handler = Handler(Looper.getMainLooper())
    var isTooltipStarted = false
    var startTime = 0L

    binding.root.setOnTouchListener { view, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          isTooltipStarted = false
          startTime = System.currentTimeMillis()
          
          // Show tooltip after 800ms (long hold for help)
          handler.postDelayed({
            if (!isTooltipStarted) {
              isTooltipStarted = true
              view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
              showTooltipForWidget(view, widgetItem)
            }
          }, 800)
        }
        
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          handler.removeCallbacksAndMessages(null)
          val holdDuration = System.currentTimeMillis() - startTime
          
          when {
            isTooltipStarted -> {
              // Tooltip already shown, do nothing
            }
            holdDuration >= 600 -> {
              // Medium hold for drag (600-800ms - drag intent)
              if (ViewCompat.startDragAndDrop(view, null, DragShadowBuilder(view), widgetItem, 0)) {
                drawerLayout.closeDrawers()
              }
            }
            else -> {
              view.performClick()
            }
          }
        }
      }
      true
    }
  }

  private fun showTooltipForWidget(anchorView: View, widgetItem: HashMap<String, Any>) {
    val context = anchorView.context
    val className =  getSuperClassName(widgetItem["className"].toString())

      className?.let {
          showTooltip(
              context = context,
              anchorView = anchorView,
              tag = className,
              category = TooltipCategory.CATEGORY_JAVA
          )
      }
  }


  override fun getItemCount(): Int {
    return tab.size
  }

  fun submitPaletteList(tab: List<HashMap<String, Any>>) {
    this.tab = tab
    notifyDataSetChanged()
  }

  class ViewHolder(var binding: LayoutPaletteItemBinding) : RecyclerView.ViewHolder(
    binding.root
  )
}
