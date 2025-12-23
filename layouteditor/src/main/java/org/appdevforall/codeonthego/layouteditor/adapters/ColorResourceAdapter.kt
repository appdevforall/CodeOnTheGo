package org.appdevforall.codeonthego.layouteditor.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ClipboardUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.adapters.models.ValuesItem
import org.appdevforall.codeonthego.layouteditor.databinding.LayoutColorItemBinding
import org.appdevforall.codeonthego.layouteditor.databinding.LayoutValuesItemDialogBinding
import org.appdevforall.codeonthego.layouteditor.tools.ColorPickerDialogFlag
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.NameErrorChecker
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils
import androidx.core.graphics.toColorInt

/**
 * Adapter class for managing and displaying a list of color resources in a RecyclerView.
 * Handles binding color data to views, editing, deleting, and generating the XML file.
 *
 * @property project The project file containing the resources.
 * @property colorList The mutable list of color values.
 */
class ColorResourceAdapter(
  private val project: ProjectFile,
  private val colorList: MutableList<ValuesItem>
) : RecyclerView.Adapter<ColorResourceAdapter.VH>() {

  class VH(var binding: LayoutColorItemBinding) : RecyclerView.ViewHolder(binding.root) {
    val colorName: TextView = binding.colorName
    val colorValue: TextView = binding.colorValue
    val colorPreview = binding.colorPreview
  }

  /**
   * Inflates the layout for the color item view.
   *
   * @param parent The ViewGroup into which the new View will be added.
   * @param viewType The view type of the new View.
   * @return A new ViewHolder that holds a View of the given view type.
   */
  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(
      LayoutColorItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  /**
   * Binds the data to the view holder.
   * Handles safe color parsing to prevent crashes if the color value is invalid.
   *
   * @param holder The ViewHolder which should be updated.
   * @param position The position of the item within the adapter's data set.
   */
  override fun onBindViewHolder(holder: VH, position: Int) {
    holder.itemView.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )

    val item = colorList[position]
    holder.colorName.text = item.name
    holder.colorValue.text = item.value

    val colorInt = getSafeColorInt(item.value)

    if (colorInt != null) {
        holder.colorValue.setTextColor(Color.DKGRAY)
        holder.colorPreview.setImageDrawable(drawCircle(colorInt))
    } else {
        holder.colorValue.text = "${item.value} (Error)"
        holder.colorValue.setTextColor(Color.RED)
        holder.colorPreview.setImageDrawable(drawCircle(Color.LTGRAY))
    }

    TooltipCompat.setTooltipText(holder.itemView, item.name)
    TooltipCompat.setTooltipText(holder.binding.menu, "Options")

    holder.binding.menu.setOnClickListener { showOptions(it, position) }
    holder.itemView.setOnClickListener { editColor(it, position) }
  }

  /**
   * Returns the total number of items in the data set held by the adapter.
   *
   * @return The total number of items.
   */
  override fun getItemCount(): Int = colorList.size

  /**
   * Generates the content for the colors.xml file based on the current list
   * and writes it to the project's file system.
   */
  fun generateColorsXml() {
    val colorsPath = project.colorsPath
    val sb = StringBuilder()
    sb.append("<resources>\n")
    for (colorItem in colorList) {
      // Generate color item code
      sb.append("\t<color name=\"")
        .append(colorItem.name)
        .append("\">")
        .append(colorItem.value)
        .append("</color>\n")
    }
    sb.append("</resources>")
    FileUtil.writeFile(colorsPath, sb.toString().trim { it <= ' ' })
  }

  /**
   * Shows a popup menu with options for the selected item (Copy Name, Delete).
   *
   * @param v The view that anchored the popup menu.
   * @param position The adapter position of the item.
   */
  private fun showOptions(v: View, position: Int) {
    val popupMenu = PopupMenu(v.context, v)
    popupMenu.inflate(R.menu.menu_values)
    popupMenu.setOnMenuItemClickListener {
      when (it.itemId) {
        R.id.menu_copy_name -> {
          ClipboardUtils.copyText(colorList[position].name)
          SBUtils.make(v, "${v.context.getString(R.string.copied)} ${colorList[position].name}")
            .setSlideAnimation().showAsSuccess()
          true
        }
        R.id.menu_delete -> {
          MaterialAlertDialogBuilder(v.context)
            .setTitle("Remove Color")
            .setMessage("Do you want to remove ${colorList[position].name}?")
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->
              if (colorList[position].name == "default_color") {
                SBUtils.make(v, v.context.getString(R.string.msg_cannot_delete_default, "color"))
                  .setFadeAnimation().setType(SBUtils.Type.INFO).show()
              } else {
                colorList.removeAt(position)
                notifyItemRemoved(position)
                generateColorsXml()
              }
            }
            .show()
          true
        }
        else -> false
      }
    }
    popupMenu.show()
  }

  /**
   * Opens a dialog to edit the name and value of a color resource.
   * Validates inputs, handles the color picker, and updates the XML file upon confirmation.
   *
   * @param v The view that triggered the edit action.
   * @param pos The adapter position of the item being edited.
   */
  @SuppressLint("SetTextI18n")
  private fun editColor(v: View, pos: Int) {
    val builder = MaterialAlertDialogBuilder(v.context)
    builder.setTitle("Edit Color")

    val bind = LayoutValuesItemDialogBinding.inflate(builder.create().layoutInflater)
    val ilName = bind.textInputLayoutName
    val etName = bind.textinputName
    val etValue = bind.textinputValue

    etName.setText(colorList[pos].name)
    etValue.setText(colorList[pos].value)
    etValue.isFocusable = false

    builder.setView(bind.root)

    etValue.setOnClickListener {
      val dialogBuilder = ColorPickerDialog.Builder(v.context)
        .setTitle("Choose Color")
        .setPositiveButton(v.context.getString(R.string.confirm),
          ColorEnvelopeListener { envelope, _ ->
            etValue.setText("#${envelope.hexCode}")
          }
        )
        .setNegativeButton(v.context.getString(R.string.cancel)) { d, _ -> d.dismiss() }
        .attachAlphaSlideBar(true)
        .attachBrightnessSlideBar(true)
        .setBottomSpace(12)

      val colorView = dialogBuilder.colorPickerView
      colorView.setFlagView(ColorPickerDialogFlag(v.context))

      val initialColor = getSafeColorInt(colorList[pos].value) ?: Color.WHITE
      colorView.setInitialColor(initialColor)

      dialogBuilder.show()
    }

    builder.setPositiveButton(R.string.okay) { _, _ ->
      val newName = etName.text.toString()
      if (colorList[pos].name == "default_color" && newName != "default_color") {
        SBUtils.make(v, v.context.getString(R.string.msg_cannot_rename_default, "color"))
          .setFadeAnimation().setType(SBUtils.Type.INFO).show()
      } else {
        colorList[pos].name = newName
      }
      colorList[pos].value = etValue.text.toString()
      notifyItemChanged(pos)
      // Generate code from all colors in list
      generateColorsXml()
    }
    builder.setNegativeButton(R.string.cancel, null)

    val dialog = builder.create()
    dialog.show()

    val textWatcher = object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) {
        NameErrorChecker.checkForValues(etName.text.toString(), ilName, dialog, colorList, pos)
      }
    }
    etName.addTextChangedListener(textWatcher)
    NameErrorChecker.checkForValues(etName.text.toString(), ilName, dialog, colorList, pos)
  }

  /**
   * Creates a circular drawable with the specified background color.
   * Automatically calculates a contrasting stroke color (border) based on luminance.
   *
   * @param backgroundColor The color to fill the circle with.
   * @return A GradientDrawable representing the colored circle.
   */
  private fun drawCircle(@ColorInt backgroundColor: Int): Drawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
      setColor(backgroundColor)
      setStroke(2, if (ColorUtils.calculateLuminance(backgroundColor) >= 0.5) {
        "#FF313131".toColorInt()
			} else {
			  "#FFD9D9D9".toColorInt()
			})
    }
  }

  /**
   * Safely parses a string value into a Color Int.
   * Attempts to handle cases where the hash symbol (#) is missing.
   *
   * @param value The color string to parse (e.g., "#FFFFFF" or "FFFFFF").
   * @return The parsed color int, or null if the string is not a valid color.
   */
  private fun getSafeColorInt(value: String): Int? {
    return try {
      value.toColorInt()
    } catch (e: Exception) {
      if (!value.startsWith("#")) {
        try {
          return "#$value".toColorInt()
        } catch (_: Exception) {
          null
        }
      } else {
        null
      }
    }
  }
}