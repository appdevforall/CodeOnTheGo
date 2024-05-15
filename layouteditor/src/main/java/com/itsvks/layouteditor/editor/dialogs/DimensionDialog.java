package com.itsvks.layouteditor.editor.dialogs;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;

import androidx.annotation.NonNull;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding;
import com.itsvks.layouteditor.utils.DimensionUtil;

public class DimensionDialog extends AttributeDialog {

  private TextInputLayout textInputLayout;
  private TextInputEditText textInputEditText;

  private String unit;

  // Constructor to create a new instance of DimensionDialog
  public DimensionDialog(Context context, @NonNull String savedValue, String unit) {
    super(context);

    this.unit = unit;

    // Inflate the textinputlayout layout
    TextinputlayoutBinding binding = TextinputlayoutBinding.inflate(getDialog().getLayoutInflater());

    // Get the root view of the textinputlayout
    textInputLayout = binding.getRoot();

    // Set the hint of the textInputLayout
    textInputLayout.setHint("Enter dimension value");

    // Set the suffix text of the textInputLayout
    textInputLayout.setSuffixText(unit);

    // Get the textInputEditText
    textInputEditText = binding.textinputEdittext;

    // Set the input type of the textInputEditText
    textInputEditText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);

    // Set the saved value or default value 0
    textInputEditText.setText(
      savedValue.isEmpty() ? "0" : DimensionUtil.getDimenWithoutSuffix(savedValue));

    // Add TextWatcher to the textInputEditText to check the error
    textInputEditText.addTextChangedListener(
        new TextWatcher() {

          // Before text changed
          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          // On text changed
          @Override
          public void onTextChanged(CharSequence text, int p2, int p3, int p4) {}

          // After text changed
          @Override
          public void afterTextChanged(Editable p1) {
            checkError();
          }
        });

    // Set the view and margin to the dialog
    setView(textInputLayout, 10);

    // Show the keyboard when the dialog open
    showKeyboardWhenOpen();
  }

  @Override
  public void show() {
    super.show();
    // Request the focus on the textInputEditText
    requestEditText(textInputEditText);
  }

  @Override
  protected void onClickSave() {
    super.onClickSave();
    // Call the listener on save and append the unit
    listener.onSave(textInputEditText.getText().toString() + unit);
  }

  // Method to check the error
  private void checkError() {
    String text = textInputEditText.getText().toString();

    // If the text is empty set the error and disable the save button
    if (text.isEmpty()) {
      setEnabled(false);
      textInputLayout.setErrorEnabled(true);
      textInputLayout.setError("Field cannot be empty!");
    } else {
      // Else enable the save button and remove the error
      setEnabled(true);
      textInputLayout.setErrorEnabled(false);
      textInputLayout.setError("");
    }
  }
}
