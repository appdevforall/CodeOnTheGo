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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
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

class ColorResourceAdapter(
  private val project: ProjectFile,
  private val colorList: MutableList<ValuesItem>
) : RecyclerView.Adapter<ColorResourceAdapter.VH>() {

  class VH(var binding: LayoutColorItemBinding) : RecyclerView.ViewHolder(binding.root) {
    val colorName: TextView = binding.colorName
    val colorValue: TextView = binding.colorValue
    val colorPreview = binding.colorPreview
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(
      LayoutColorItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

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
            .setTitle(R.string.remove_color_dialog_title)
            .setMessage(v.context.getString(R.string.msg_confirm_remove_color, colorList[position].name))
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

    @SuppressLint("SetTextI18n")
    private fun editColor(itemView: View, pos: Int) {
        val context = itemView.context
        val binding = LayoutValuesItemDialogBinding.inflate(LayoutInflater.from(context))
        val item = colorList[pos]

        // 1. Setup Views
        binding.textinputName.setText(item.name)
        binding.textinputValue.setText(item.value)

        // 2. Setup Dialog
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_color_dialog_title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.okay) { _, _ ->
                performSaveColor(itemView, pos, binding)
            }
            .create()

        // 3. Setup Logic Components
        setupColorPickerInteraction(itemView, binding)
        setupValidation(dialog, binding, pos)

        dialog.show()

        // Initial Check (Trigger validation immediately to set button state)
        validateEditForm(dialog, binding, pos)
    }

    private fun performSaveColor(v: View, pos: Int, binding: LayoutValuesItemDialogBinding) {
        val newName = binding.textinputName.text.toString()
        val newValue = binding.textinputValue.text.toString()
        val item = colorList[pos]

        if (item.name == "default_color" && newName != "default_color") {
            SBUtils.make(v, v.context.getString(R.string.msg_cannot_rename_default, "color"))
                .setFadeAnimation()
                .setType(SBUtils.Type.INFO)
                .show()
            return
        }

        item.name = newName
        item.value = newValue

        notifyItemChanged(pos)
        generateColorsXml()
    }

    private fun setupColorPickerInteraction(v: View, binding: LayoutValuesItemDialogBinding) {
        binding.textInputLayoutValue.setEndIconOnClickListener {
            val initialColorInt = getSafeColorInt(binding.textinputValue.text.toString()) ?: Color.WHITE

            val builder = ColorPickerDialog.Builder(v.context)
                .setTitle(R.string.color_picker_dialog_title)
                .setPositiveButton(v.context.getString(R.string.confirm),
                    ColorEnvelopeListener { envelope, _ ->
                        binding.textinputValue.setText("#${envelope.hexCode}")
                        binding.textInputLayoutValue.error = null
                    }
                )
                .setNegativeButton(v.context.getString(R.string.cancel)) { d, _ -> d.dismiss() }
                .attachAlphaSlideBar(true)
                .attachBrightnessSlideBar(true)
                .setBottomSpace(12)

            builder.colorPickerView.apply {
                setFlagView(ColorPickerDialogFlag(v.context))
                setInitialColor(initialColorInt)
            }
            builder.show()
        }
    }

    /**
     * Sets up listeners for both Name and Value fields.
     */
    private fun setupValidation(dialog: AlertDialog, binding: LayoutValuesItemDialogBinding, pos: Int) {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // Call the central validation method whenever text changes in EITHER field
                validateEditForm(dialog, binding, pos)
            }
        }

        binding.textinputName.addTextChangedListener(textWatcher)
        binding.textinputValue.addTextChangedListener(textWatcher)
    }

    /**
     * Central validation logic. Checks both Name and Value validity.
     * Updates UI errors and Button state.
     */
    private fun validateEditForm(dialog: AlertDialog, binding: LayoutValuesItemDialogBinding, pos: Int) {
        val nameInput = binding.textinputName.text.toString()
        val valueInput = binding.textinputValue.text.toString()

        // 1. Check Name (Using helper)
        NameErrorChecker.checkForValues(nameInput, binding.textInputLayoutName, dialog, colorList, pos)
        // NameErrorChecker sets the error on the layout, so we check if error is null
        val isNameValid = binding.textInputLayoutName.error == null && nameInput.isNotBlank()

        // 2. Check Color Value
        val isValidColor = getSafeColorInt(valueInput) != null
        if (isValidColor) {
            binding.textInputLayoutValue.error = null
            binding.textInputLayoutValue.isErrorEnabled = false
        } else {
            binding.textInputLayoutValue.error = dialog.context.getString(R.string.error_invalid_color)
        }

        // 3. Update Button State (Only enabled if BOTH are valid)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = isNameValid && isValidColor
    }

    private fun drawCircle(@ColorInt backgroundColor: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            setColor(backgroundColor)
            setStroke(2, getContrastStrokeColor(backgroundColor))
        }
    }

    private fun getContrastStrokeColor(@ColorInt color: Int): Int {
        if (ColorUtils.calculateLuminance(color) >= 0.5) {
            return "#FF313131".toColorInt()
        }
        return "#FFD9D9D9".toColorInt()
    }

    private fun getSafeColorInt(value: String): Int? {
        val directParse = runCatching { value.toColorInt() }.getOrNull()
        if (directParse != null) return directParse

        if (!value.startsWith("#")) {
            val fixedParse = runCatching { "#$value".toColorInt() }.getOrNull()
            if (fixedParse != null) return fixedParse
        }
        return null
    }
}