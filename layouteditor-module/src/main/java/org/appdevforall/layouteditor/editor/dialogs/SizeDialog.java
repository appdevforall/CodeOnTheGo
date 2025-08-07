package com.itsvks.layouteditor.editor.dialogs;

import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatRadioButton;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.R;
import com.itsvks.layouteditor.databinding.LayoutSizeDialogBinding;
import com.itsvks.layouteditor.utils.DimensionUtil;

public class SizeDialog extends AttributeDialog {

  private TextInputLayout textInputLayout;
  private TextInputEditText textInputEditText;

  private RadioGroup group;

  /**
   * Constructor to create SizeDialog instance
   *
   * @param context The context of the activity
   * @param savedValue The saved value of the attribute
   */
  public SizeDialog(Context context, @NonNull String savedValue) {
    super(context);

    LayoutSizeDialogBinding binding = LayoutSizeDialogBinding.inflate(getDialog().getLayoutInflater());

    final ViewGroup dialogView = binding.getRoot();
    group = binding.radiogroup;

    final AppCompatRadioButton rbMatchParent = binding.rbMatchParent;
    final AppCompatRadioButton rbWrapContent = binding.rbWrapContent;
    final AppCompatRadioButton rbFixedValue = binding.rbFixedValue;

    textInputLayout = dialogView.findViewById(R.id.textinput_layout);
    textInputLayout.setHint("Enter dimension value");
    textInputLayout.setSuffixText("dp");

    textInputEditText = dialogView.findViewById(R.id.textinput_edittext);
    textInputEditText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
    textInputLayout.setVisibility(View.GONE);

    // Check if savedValue is "match_parent", "wrap_content", or a fixed value
    if (!savedValue.isEmpty()) {
      if (savedValue.equals("match_parent")) {
        rbMatchParent.setChecked(true);
      } else if (savedValue.equals("wrap_content")) {
        rbWrapContent.setChecked(true);
      } else {
        rbFixedValue.setChecked(true);
        textInputLayout.setVisibility(View.VISIBLE);
        textInputEditText.setText(DimensionUtil.getDimenWithoutSuffix(savedValue));
      }
    }

    // Add a TextChangeListener to the TextInputEditText to check for an error
    textInputEditText.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence text, int p2, int p3, int p4) {}

          @Override
          public void afterTextChanged(Editable p1) {
            checkError();
          }
        });

    // Set onCheckedChangeListener to the RadioGroup
    group.setOnCheckedChangeListener(
      (p1, id) -> {
        if (id == R.id.rb_fixed_value) {
          dialogView.setLayoutTransition(new LayoutTransition());
          textInputLayout.setVisibility(View.VISIBLE);
          checkError();
        } else {
          dialogView.setLayoutTransition(new LayoutTransition());
          textInputLayout.setVisibility(View.GONE);
          setEnabled(true);
        }
      });

    setView(dialogView, 10);
  }

  /** Method to check for an error */
  private void checkError() {
    String text = textInputEditText.getText().toString();

    // Check if the field is empty, and set the appropriate error messages
    if (text.isEmpty()) {
      setEnabled(false);
      textInputLayout.setErrorEnabled(true);
      textInputLayout.setError("Field cannot be empty!");
    } else {
      setEnabled(true);
      textInputLayout.setErrorEnabled(false);
      textInputLayout.setError("");
    }
  }

  /** Method to save the value of the attribute */
  @SuppressLint("NonConstantResourceId")
  @Override
  protected void onClickSave() {
    String value = "";

    // Get the value of the attribute based on the checked radio button
    int checkedRadioButtonId = group.getCheckedRadioButtonId();
    if (checkedRadioButtonId == R.id.rb_match_parent) {
      value = "match_parent";
    } else if (checkedRadioButtonId == R.id.rb_wrap_content) {
      value = "wrap_content";
    } else if (checkedRadioButtonId == R.id.rb_fixed_value) {
      value = textInputEditText.getText().toString() + DimensionUtil.DP;
    }

    listener.onSave(value);
  }
}
