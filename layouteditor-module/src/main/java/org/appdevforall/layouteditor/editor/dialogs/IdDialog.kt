package com.itsvks.layouteditor.editor.dialogs

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding
import com.itsvks.layouteditor.managers.IdManager.getIds
import java.util.regex.Pattern

class IdDialog(context: Context, savedValue: String) : AttributeDialog(context) {
  private val textInputLayout: TextInputLayout
  private val textInputEditText: TextInputEditText

  private val ids: MutableList<String>

  init {
    // Initialize the binding and savedValue variables
    val binding = TextinputlayoutBinding.inflate(dialog.layoutInflater)

    // Get all the IDs from the IdManager
    ids = getIds()

    // Initialize the TextInputLayout and set hint and prefix text
    textInputLayout = binding.root
    textInputLayout.apply {
      hint = "Enter new ID"
      prefixText = "@+id/"
    }

    // Initialize the TextInputEditText and set the text from the savedValue
    textInputEditText = binding.textinputEdittext
    if (savedValue.isNotEmpty()) {
      ids.remove(savedValue.replace("@+id/", ""))
      textInputEditText.setText(savedValue.replace("@+id/", ""))
    }

    // Add a TextWatcher to the TextInputEditText for checking errors
    textInputEditText.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {
        }

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {
        }

        override fun afterTextChanged(p1: Editable) {
          checkErrors()
        }
      })

    // Set the view with a margin of 10dp
    setView(textInputLayout, 10)
    showKeyboardWhenOpen()
  }

  /**
   * Check errors in the TextInputEditText
   */
  private fun checkErrors() {
    val text = textInputEditText.text.toString()

    // Check if the TextInputEditText is empty
    if (text.isEmpty()) {
      textInputLayout.isErrorEnabled = true
      textInputLayout.error = "Field cannot be empty!"
      setEnabled(false)
      return
    }

    if (!Pattern.matches("[a-z][A-Za-z0-9_\\s]*", text)) {
      textInputLayout.isErrorEnabled = true
      textInputLayout.error = dialog.context.getString(R.string.msg_symbol_not_allowed)
      setEnabled(false)
      return
    }

    // Check if the ID is already taken
    for (id in ids) {
      if (id == text) {
        textInputLayout.isErrorEnabled = true
        textInputLayout.error = "Current ID is unavailable!"
        setEnabled(false)
        return
      }
    }

    // No errors detected
    textInputLayout.isErrorEnabled = false
    textInputLayout.error = ""
    setEnabled(true)
  }

  override fun show() {
    super.show()

    // Request focus to the TextInputEditText and check errors
    requestEditText(textInputEditText)
    checkErrors()
  }

  override fun onClickSave() {
    // Call the onSave method and pass the ID
    listener.onSave(
      "@+id/${textInputEditText.text.toString().lowercase().replace(" ".toRegex(), "_")}"
    )
  }
}