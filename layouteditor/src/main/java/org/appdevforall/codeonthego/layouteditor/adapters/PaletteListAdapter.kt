package org.appdevforall.codeonthego.layouteditor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.View.DragShadowBuilder
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager.showTooltip
import com.itsaky.androidide.utils.setupGestureHandling
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
    binding.root.contentDescription = widgetItem["name"].toString()
    binding.root.setupGestureHandling(
        onLongPress = { view -> showTooltipForWidget(view, widgetItem) },
        onDrag = { view ->
            if (ViewCompat.startDragAndDrop(view, null, DragShadowBuilder(view), widgetItem, 0)) {
                drawerLayout.closeDrawers()
            }
        }
    )

    binding
      .root.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )
  }


  private fun showTooltipForWidget(anchorView: View, widgetItem: HashMap<String, Any>) {
    val className =  getSuperClassName(widgetItem["className"].toString())

      className?.let {
          showTooltip(
              context = anchorView.context,
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
