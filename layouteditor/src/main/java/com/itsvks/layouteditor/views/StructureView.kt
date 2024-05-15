package com.itsvks.layouteditor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.webkit.WebView
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CalendarView
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MultiAutoCompleteTextView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RatingBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.SearchView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Spinner
import android.widget.Switch
import android.widget.TabHost
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextClock
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.VideoView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.TooltipCompat
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.LayoutStructureViewItemBinding
import com.itsvks.layouteditor.managers.IdManager.idMap

class StructureView(context: Context?, attrs: AttributeSet?) : LinearLayoutCompat(
  context!!, attrs
), View.OnClickListener {
  private val inflater: LayoutInflater = LayoutInflater.from(context)
  private val paint = Paint()
  private val pointRadius: Int
  private val textViewMap: MutableMap<TextView, View> = HashMap()
  private val viewTextMap: MutableMap<View, TextView> = HashMap()

  var onItemClickListener: ((View) -> Unit)? = null

  /**
   * This is the constructor of the StructureView class which takes context and attributeSet as
   * parameters. It creates a new Paint object, sets its color and anti-alias. It also sets the
   * orientation of this view to VERTICAL and sets the default OnItemClickListener.
   */
  init {
    val primaryColor =
      MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    paint.color = primaryColor
    paint.isAntiAlias = true
    paint.strokeWidth = getDip(1).toFloat()

    pointRadius = getDip(3)

    orientation = VERTICAL
  }

  /** This method clears all Views and HashMaps stored in this view.  */
  fun clear() {
    removeAllViews()
    textViewMap.clear()
    viewTextMap.clear()
  }

  /**
   * This method sets a View to this view. It clears all the stored views and hashmaps, and then
   * calls the peek() method to peek into the View.
   */
  fun setView(view: View) {
    textViewMap.clear()
    viewTextMap.clear()
    removeAllViews()
    peek(view, 1)
  }

  /**
   * This method recursively calls itself to add TextViews for each View inside the ViewGroup. It
   * also stores the TextViews and Views in their respective hashmaps.
   */
  private fun peek(view: View, depth: Int) {
    var nextDepth = depth
    val binding =
      LayoutStructureViewItemBinding.inflate(inflater, null, false)
    val viewName = binding.viewName
    val viewId = binding.viewId
    val icon = binding.icon
    if (view.id == -1 || idMap[view] == null) {
      viewId.visibility = GONE
      viewName.translationY = 0f
      viewId.translationY = 0f
      TooltipCompat.setTooltipText(
        binding.mainView, view.javaClass.superclass.simpleName
      )
    } else {
      viewName.translationY = getDip(-7).toFloat()
      viewId.translationY = getDip(-3).toFloat()
      viewId.visibility = VISIBLE
      viewId.text = idMap[view]
      TooltipCompat.setTooltipText(binding.mainView, idMap[view])
    }
    if (view is LinearLayout && view !is RadioGroup) {
      val orientation =
        if (view.orientation == LinearLayout.HORIZONTAL) "horizontal" else "vertical"
      val imgResId = imgMap[LinearLayout::class.java.simpleName + orientation]!!

      icon.setImageResource(imgResId)
      viewName.text = String.format("%s (%s)", LinearLayout::class.java.simpleName, orientation)
    } else {
      val viewSimpleName = view.javaClass.superclass.simpleName
      var imageResource = imgMap[viewSimpleName]
      if (imageResource == null) {
        imageResource = imgMap["_unknown"]
      }
      icon.setImageResource(imageResource!!)
      viewName.text = viewSimpleName
    }

    binding.mainView.setOnClickListener(this)

    addView(binding.root)

    val params =
      binding.root.layoutParams as LayoutParams
    params.leftMargin = depth * getDip(15)

    textViewMap[viewName] = view
    viewTextMap[view] = viewName

    if (view is ViewGroup) {
      val group = view
      if (group !is CalendarView
        && group !is SearchView
        && group !is NavigationView
        && group !is BottomNavigationView
        && group !is TabLayout
      ) {
        nextDepth++

        for (i in 0 until group.childCount) {
          val child = group.getChildAt(i)
          peek(child, nextDepth)
        }
      }
    }
  }

  /** This method is called to draw rectangles, lines, and circles for each TextView in the view.  */
  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)

    for (text in textViewMap.keys) {
      val view = textViewMap[text]
      val parent = text.parent.parent as ViewGroup

      if (view is ViewGroup && view.childCount > 0) {
        val x = parent.x
        val y = parent.y + parent.height.toFloat() / 2

        val group = view
        if (group !is CalendarView
          && group !is SearchView
          && group !is NavigationView
          && group !is BottomNavigationView
          && group !is TabLayout
        ) {
          canvas.drawRect(
            x - pointRadius, y - pointRadius, x + pointRadius, y + pointRadius, paint
          )
          for (i in 0 until group.childCount) {
            val current = viewTextMap[group.getChildAt(i)]
            val currentParent = current!!.parent.parent as ViewGroup
            canvas.drawLine(
              parent.x,
              parent.y + parent.height.toFloat() / 2,
              parent.x,
              currentParent.y + currentParent.height.toFloat() / 2,
              paint
            )
            canvas.drawLine(
              parent.x,
              currentParent.y + currentParent.height.toFloat() / 2,
              currentParent.x,
              currentParent.y + currentParent.height.toFloat() / 2,
              paint
            )
          }
        } else {
          canvas.drawCircle(
            parent.x, parent.y + parent.height.toFloat() / 2, pointRadius.toFloat(), paint
          )
        }
      } else {
        canvas.drawCircle(
          parent.x, parent.y + parent.height.toFloat() / 2, pointRadius.toFloat(), paint
        )
      }
    }
  }

  /**
   * This method is called when a TextView is clicked, and it calls the OnItemClickListener's
   * onItemClick method.
   */
  override fun onClick(v: View) {
    if (v is ViewGroup) {
      for (i in 0 until v.childCount) {
        val child = v.getChildAt(i)
        if (child.id == R.id.view_name) textViewMap[child as TextView]?.let {
          onItemClickListener?.invoke(
            it
          )
        }
      }
    }
  }

  /** This method is used to convert the input into the equivalent dip value.  */
  private fun getDip(input: Int): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, input.toFloat(), context.resources.displayMetrics
    ).toInt()
  }

  companion object {
    var imgMap: MutableMap<String, Int> = HashMap()

    init {
      imgMap["_unknown"] = R.mipmap.ic_palette_unknown_view
      imgMap[TextView::class.java.simpleName] = R.mipmap.ic_palette_text_view
      imgMap[EditText::class.java.simpleName] = R.mipmap.ic_palette_edit_text
      imgMap[Button::class.java.simpleName] =
        R.mipmap.ic_palette_button
      imgMap[ImageButton::class.java.simpleName] =
        R.mipmap.ic_palette_image_button
      imgMap[ImageView::class.java.simpleName] = R.mipmap.ic_palette_image_view
      imgMap[VideoView::class.java.simpleName] = R.mipmap.ic_palette_video_view
      imgMap[AutoCompleteTextView::class.java.simpleName] =
        R.mipmap.ic_palette_auto_complete_text_view
      imgMap[MultiAutoCompleteTextView::class.java.simpleName] =
        R.mipmap.ic_palette_multi_auto_complete_text_view
      imgMap[CheckedTextView::class.java.simpleName] =
        R.mipmap.ic_palette_checked_text_view
      imgMap[CheckBox::class.java.simpleName] = R.mipmap.ic_palette_check_box
      imgMap[RadioButton::class.java.simpleName] = R.mipmap.ic_palette_radio_button
      imgMap[RadioGroup::class.java.simpleName] =
        R.mipmap.ic_palette_radio_group
      imgMap[ToggleButton::class.java.simpleName] = R.mipmap.ic_palette_toggle_button
      imgMap[Switch::class.java.simpleName] = R.mipmap.ic_palette_switch
      imgMap[View::class.java.simpleName] = R.mipmap.ic_palette_view
      imgMap[WebView::class.java.simpleName] = R.mipmap.ic_palette_web_view
      imgMap[CalendarView::class.java.simpleName] = R.mipmap.ic_palette_calendar_view
      imgMap[ProgressBar::class.java.simpleName] = R.mipmap.ic_palette_progress_bar
      imgMap[ProgressBar::class.java.simpleName + "horizontal"] =
        R.mipmap.ic_palette_progress_bar_horizontal
      imgMap[SeekBar::class.java.simpleName] = R.mipmap.ic_palette_seek_bar
      imgMap[RatingBar::class.java.simpleName] = R.mipmap.ic_palette_rating_bar
      imgMap[TextureView::class.java.simpleName] =
        R.mipmap.ic_palette_texture_view
      imgMap[SurfaceView::class.java.simpleName] = R.mipmap.ic_palette_surface_view
      imgMap[SearchView::class.java.simpleName] =
        R.mipmap.ic_palette_search_view
      imgMap[LinearLayout::class.java.simpleName + "horizontal"] =
        R.mipmap.ic_palette_linear_layout_horz
      imgMap[LinearLayout::class.java.simpleName + "vertical"] =
        R.mipmap.ic_palette_linear_layout_vert
      imgMap[FrameLayout::class.java.simpleName] = R.mipmap.ic_palette_frame_layout
      imgMap[TableLayout::class.java.simpleName] = R.mipmap.ic_palette_table_layout
      imgMap[TableRow::class.java.simpleName] = R.mipmap.ic_palette_table_row
      imgMap[Space::class.java.simpleName] = R.mipmap.ic_palette_space
      imgMap[Spinner::class.java.simpleName] = R.mipmap.ic_palette_spinner
      imgMap[ScrollView::class.java.simpleName] = R.mipmap.ic_palette_scroll_view
      imgMap[HorizontalScrollView::class.java.simpleName] =
        R.mipmap.ic_palette_horizontal_scroll_view
      imgMap[ViewStub::class.java.simpleName] = R.mipmap.ic_palette_view_stub
      imgMap["include"] = R.mipmap.ic_palette_include
      imgMap[GridLayout::class.java.simpleName] =
        R.mipmap.ic_palette_grid_layout
      imgMap[GridView::class.java.simpleName] = R.mipmap.ic_palette_grid_view
      imgMap[RecyclerView::class.java.simpleName] = R.mipmap.ic_palette_recycler_view
      imgMap[ListView::class.java.simpleName] = R.mipmap.ic_palette_list_view
      imgMap[TabHost::class.java.simpleName] = R.mipmap.ic_palette_tab_host
      imgMap[RelativeLayout::class.java.simpleName] = R.mipmap.ic_palette_relative_layout
      imgMap[Chip::class.java.simpleName] = R.mipmap.ic_palette_chip
      imgMap[ChipGroup::class.java.simpleName] = R.mipmap.ic_palette_chip_group
      imgMap[FloatingActionButton::class.java.simpleName] =
        R.mipmap.ic_palette_floating_action_button
      imgMap[NestedScrollView::class.java.simpleName] = R.mipmap.ic_palette_nested_scroll_view
      imgMap[ViewPager::class.java.simpleName] = R.mipmap.ic_palette_view_pager
      imgMap[ViewPager2::class.java.simpleName] = R.mipmap.ic_palette_view_pager
      imgMap[CardView::class.java.simpleName] = R.mipmap.ic_palette_card_view
      imgMap[TextClock::class.java.simpleName] = R.mipmap.ic_palette_text_clock
      imgMap[AppBarLayout::class.java.simpleName] = R.mipmap.ic_palette_app_bar_layout
      imgMap[NavigationView::class.java.simpleName] = R.mipmap.ic_palette_navigation_view
      imgMap[ConstraintLayout::class.java.simpleName] =
        R.mipmap.ic_palette_constraint_layout
      imgMap[BottomNavigationView::class.java.simpleName] =
        R.mipmap.ic_palette_bottom_navigation_view
    }
  }
}
