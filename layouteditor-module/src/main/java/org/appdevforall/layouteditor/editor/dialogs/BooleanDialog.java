package com.itsvks.layouteditor.editor.dialogs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatRadioButton;

import com.itsvks.layouteditor.R;
import com.itsvks.layouteditor.databinding.LayoutBooleanDialogBinding;

public class BooleanDialog extends AttributeDialog {

  private LayoutBooleanDialogBinding binding;

  /**
   * Constructor for BooleanDialog
   *
   * @param context    current context
   * @param savedValue previously saved value
   */
  public BooleanDialog(Context context, @NonNull String savedValue) {
    super(context);

    // Inflate the layout for this dialog
    binding = LayoutBooleanDialogBinding.inflate(getDialog().getLayoutInflater());

    // Initialize radio buttons
    AppCompatRadioButton rbTrue = binding.rbTrue;
    AppCompatRadioButton rbFalse = binding.rbFalse;

    // Set view padding
    setView(binding.getRoot(), 10, 20, 10, 0);

    // Check radio button for previously saved value
    if (!savedValue.isEmpty()) {
      if (savedValue.equals("true")) {
        rbTrue.setChecked(true);
      } else {
        rbFalse.setChecked(true);
      }
    }
  }

  /**
   * Method called when save button is clicked
   */
  @Override
  protected void onClickSave() {
    super.onClickSave();

    // Get the checked radio button id
    int checkedRadioButtonId = binding.getRoot().getCheckedRadioButtonId();

    // Check if radio button is true or false
    String value = checkedRadioButtonId == R.id.rbTrue ? "true" : "false";

    // Invoke the listener to save the value
    listener.onSave(value);
  }
}
