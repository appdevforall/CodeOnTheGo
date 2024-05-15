package com.itsvks.layouteditor.editor.dialogs;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.NonNull;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding;
import com.itsvks.layouteditor.managers.IdManager;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.List;

public class IdDialog extends AttributeDialog {

  private TextInputLayout textInputLayout;
  private TextInputEditText textInputEditText;

  private List<String> ids;

  /**
   * Constructor of IdDialog
   * 
   * @param context The context of the application
   * @param savedValue The saved value of the ID
   */
  public IdDialog(Context context, @NonNull String savedValue) {
    super(context);

    // Initialize the binding and savedValue variables
    TextinputlayoutBinding binding = TextinputlayoutBinding.inflate(getDialog().getLayoutInflater());

    // Get all the IDs from the IdManager
    ids = IdManager.getIds();

    // Initialize the TextInputLayout and set hint and prefix text
    textInputLayout = binding.getRoot();
    textInputLayout.setHint("Enter new ID");
    textInputLayout.setPrefixText("@+id/");

    // Initialize the TextInputEditText and set the text from the savedValue
    textInputEditText = binding.textinputEdittext;
    if (!savedValue.isEmpty()) {
      ids.remove(savedValue.replace("@+id/", ""));
      textInputEditText.setText(savedValue.replace("@+id/", ""));
    }

    // Add a TextWatcher to the TextInputEditText for checking errors
    textInputEditText.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void afterTextChanged(Editable p1) {
            checkErrors();
          }
        });

    // Set the view with a margin of 10dp
    setView(textInputLayout, 10);
    showKeyboardWhenOpen();
  }

  /**
   * Check errors in the TextInputEditText
   */
  private void checkErrors() {
    String text = textInputEditText.getText().toString();

    // Check if the TextInputEditText is empty
    if (text.isEmpty()) {
      textInputLayout.setErrorEnabled(true);
      textInputLayout.setError("Field cannot be empty!");
      setEnabled(false);
      return;
    }

    // Check if the text matches the pattern of only small letters(a-z) and numbers
    if (!Pattern.matches("[a-z_][a-z0-9_]*", text)) {
      textInputLayout.setErrorEnabled(true);
      textInputLayout.setError("Only small letters(a-z) and numbers!");
      setEnabled(false);
      return;
    }

    // Check if the ID is already taken
    for (String id : ids) {
      if (id.equals(text)) {
        textInputLayout.setErrorEnabled(true);
        textInputLayout.setError("Current ID is unavailable!");
        setEnabled(false);
        return;
      }
    }

    // No errors detected
    textInputLayout.setErrorEnabled(false);
    textInputLayout.setError("");
    setEnabled(true);
  }

  @Override
  public void show() {
    super.show();

    // Request focus to the TextInputEditText and check errors
    requestEditText(textInputEditText);
    checkErrors();
  }

  @Override
  protected void onClickSave() {
    // Call the onSave method and pass the ID
    listener.onSave("@+id/" + textInputEditText.getText().toString());
  }
}