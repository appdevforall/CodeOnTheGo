package com.itsvks.layouteditor.editor.dialogs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatSeekBar;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.R;
import com.itsvks.layouteditor.databinding.LayoutColorDialogBinding;
import com.itsvks.layouteditor.views.ColorView;

import java.util.regex.Pattern;

public class ColorDialog extends AttributeDialog
    implements AppCompatSeekBar.OnSeekBarChangeListener {

  // Declaring Views
  private ColorView colorPreview;
  private AppCompatSeekBar seekAlpha;
  private AppCompatSeekBar seekRed;
  private AppCompatSeekBar seekGreen;
  private AppCompatSeekBar seekBlue;
  private TextInputLayout inputLayout, aInputLayout, rInputLayout, gInputLayout, bInputLayout;
  private TextInputEditText editText, aEdittext, rEdittext, gEdittext, bEdittext;

  /**
   * Constructor of ColorDialog
   *
   * @param context Application Context
   * @param savedValue Saved Color Value
   */
  public ColorDialog(Context context, String savedValue) {
    super(context);

    // Inflate Layout Binding
    com.itsvks.layouteditor.databinding.LayoutColorDialogBinding binding = LayoutColorDialogBinding.inflate(getDialog().getLayoutInflater());

    // Getting View from binding
    final View dialogView = binding.getRoot();

    // Initializing Views
    colorPreview = binding.colorPreview;
    seekAlpha = binding.seekAlpha;
    seekRed = binding.seekRed;
    seekGreen = binding.seekGreen;
    seekBlue = binding.seekBlue;
    inputLayout = dialogView.findViewById(R.id.textinput_layout);
    aInputLayout = binding.ainputLayout;
    rInputLayout = binding.rinputLayout;
    gInputLayout = binding.ginputLayout;
    bInputLayout = binding.binputLayout;
    editText = dialogView.findViewById(R.id.textinput_edittext);
    aEdittext = binding.ainputEdittext;
    rEdittext = binding.rinputEdittext;
    gEdittext = binding.ginputEdittext;
    bEdittext = binding.binputEdittext;

    // Setting Seekbar Progress and Listener
    setSeekbarProgressAndListener(seekAlpha);
    setSeekbarProgressAndListener(seekRed);
    setSeekbarProgressAndListener(seekGreen);
    setSeekbarProgressAndListener(seekBlue);

    // Setting UI Values
    setUIValues(savedValue);

    // Setting TextWatcher on EditText
    setTextWatcherOnEditText();

    setView(dialogView, 10);
  }

  /**
   * Sets Seekbar Progress and Listener
   *
   * @param seekBar SeekBar to set
   */
  private void setSeekbarProgressAndListener(@NonNull AppCompatSeekBar seekBar) {
    seekBar.setOnSeekBarChangeListener(this);
    seekBar.setMax(255);
    seekBar.setProgress(255);
  }

  /**
   * Sets UI Values
   *
   * @param savedValue Saved Color Value
   */
  private void setUIValues(@NonNull String savedValue) {
    inputLayout.setHint("Enter custom HEX code");
    inputLayout.setPrefixText("#");
    editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(8)});
    aEdittext.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
    rEdittext.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
    gEdittext.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
    bEdittext.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
    if (!savedValue.isEmpty()) {
      colorPreview.setColor(Color.parseColor(savedValue));
      aEdittext.setText(String.valueOf(Color.alpha(colorPreview.getColor())));
      aEdittext.setTextColor(colorPreview.getInvertedRGB());
      rEdittext.setText(String.valueOf(Color.red(colorPreview.getColor())));
      rEdittext.setTextColor(colorPreview.getInvertedRGB());
      gEdittext.setText(String.valueOf(Color.green(colorPreview.getColor())));
      gEdittext.setTextColor(colorPreview.getInvertedRGB());
      bEdittext.setText(String.valueOf(Color.blue(colorPreview.getColor())));
      bEdittext.setTextColor(colorPreview.getInvertedRGB());
      updateARGB(colorPreview.getColor());
      updateSeekbars(colorPreview.getColor());
      updateEditText();
    }
  }

  /** Sets TextWatcher on EditText */
  private void setTextWatcherOnEditText() {
    editText.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void afterTextChanged(Editable p1) {
            if (!editText.getText().toString().isEmpty()) checkHexErrors(editText.getText().toString());
          }
        });
    aEdittext.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {
            checkAlphaErrors(p1.toString());
          }

          @Override
          public void afterTextChanged(Editable p1) {}
        });
    rEdittext.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {
            checkRedErrors(p1.toString());
          }

          @Override
          public void afterTextChanged(Editable p1) {}
        });
    gEdittext.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {
            checkGreenErrors(p1.toString());
          }

          @Override
          public void afterTextChanged(Editable p1) {}
        });
    bEdittext.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {
            checkBlueErrors(p1.toString());
          }

          @Override
          public void afterTextChanged(Editable p1) {}
        });
  }

  /** Called when Save button is clicked */
  @Override
  public void onClickSave() {
    listener.onSave("#" + colorPreview.getHexColor());
  }
    
  /**
   * Checks for Alpha Errors
   *
   * @param alpha user entered Alpha value
   */
  private void checkAlphaErrors(@NonNull String alpha) {
    if (!alpha.isEmpty() && Pattern.matches("[0-9]*", alpha)) {
      aInputLayout.setErrorEnabled(false);
      aInputLayout.setError("");
      setEnabled(true);
      if (Integer.parseInt(alpha) != Color.alpha(colorPreview.getColor())) {
        colorPreview.setAlpha(Integer.parseInt(alpha));
        updateSeekbars(colorPreview.getColor());
        updateEditText();
      }
      return;
    }
    aInputLayout.setErrorEnabled(true);
    aInputLayout.setError("Invalid Alpha value");
    setEnabled(false);
  }
    
  /**
   * Checks for Red Errors
   *
   * @param red user entered RED value
   */
  private void checkRedErrors(@NonNull String red) {
    if (!red.isEmpty() && Pattern.matches("[0-9]*", red)) {
      rInputLayout.setErrorEnabled(false);
      rInputLayout.setError("");
      setEnabled(true);
      if (Integer.parseInt(red) != Color.alpha(colorPreview.getColor())) {
        colorPreview.setRed(Integer.parseInt(red));
        updateSeekbars(colorPreview.getColor());
        updateEditText();
      }
      return;
    }
    rInputLayout.setErrorEnabled(true);
    rInputLayout.setError("Invalid Red value");
    setEnabled(false);
  }
    
  /**
   * Checks for Green Errors
   *
   * @param green user entered GREEN value
   */
  private void checkGreenErrors(@NonNull String green) {
    if (!green.isEmpty() && Pattern.matches("[0-9]*", green)) {
      gInputLayout.setErrorEnabled(false);
      gInputLayout.setError("");
      setEnabled(true);
      if (Integer.parseInt(green) != Color.alpha(colorPreview.getColor())) {
        colorPreview.setGreen(Integer.parseInt(green));
        updateSeekbars(colorPreview.getColor());
        updateEditText();
      }
      return;
    }
    gInputLayout.setErrorEnabled(true);
    gInputLayout.setError("Invalid Green value");
    setEnabled(false);
  }
    
  /**
   * Checks for Blue Errors
   *
   * @param blue user entered BLUE value
   */
  private void checkBlueErrors(@NonNull String blue) {
    if (!blue.isEmpty() && Pattern.matches("[0-9]*", blue)) {
      bInputLayout.setErrorEnabled(false);
      bInputLayout.setError("");
      setEnabled(true);
      if (Integer.parseInt(blue) != Color.alpha(colorPreview.getColor())) {
        colorPreview.setBlue(Integer.parseInt(blue));
        updateSeekbars(colorPreview.getColor());
        updateEditText();
      }
      return;
    }
    bInputLayout.setErrorEnabled(true);
    bInputLayout.setError("Invalid Blue value");
    setEnabled(false);
  }

  /**
   * Checks for Hex Errors
   *
   * @param hex user entered HEX value
   */
  private void checkHexErrors(String hex) {
    if (Pattern.matches("[a-fA-F0-9]{6}", hex) || Pattern.matches("[a-fA-F0-9]{8}", hex)) {
      colorPreview.setColor(Color.parseColor("#" + hex));
      updateSeekbars(colorPreview.getColor());
      updateARGB(colorPreview.getColor());
      inputLayout.setErrorEnabled(false);
      inputLayout.setError("");
      setEnabled(true);
      return;
    }
    inputLayout.setErrorEnabled(true);
    inputLayout.setError("Invalid HEX value");
    setEnabled(false);
  }

  /**
   * Updates ARGB with Color Values
   *
   * @param color Color to be set
   */
  private void updateARGB(int color) {
    int a = Color.alpha(color);
    int r = Color.red(color);
    int g = Color.green(color);
    int b = Color.blue(color);
        
    if (a != (aEdittext.getText().toString().isEmpty() ? 0 : Integer.parseInt(aEdittext.getText().toString()))) {
      aEdittext.setText(String.valueOf(a));
    }

    if (r != (rEdittext.getText().toString().isEmpty() ? 0 : Integer.parseInt(rEdittext.getText().toString()))) {
      rEdittext.setText(String.valueOf(r));
    }

    if (g != (gEdittext.getText().toString().isEmpty() ? 0 : Integer.parseInt(gEdittext.getText().toString()))) {
      gEdittext.setText(String.valueOf(g));
    }

    if (b != (bEdittext.getText().toString().isEmpty() ? 0 : Integer.parseInt(bEdittext.getText().toString()))) {
      bEdittext.setText(String.valueOf(b));
    }
        
    aEdittext.setTextColor(colorPreview.getInvertedRGB());
    rEdittext.setTextColor(colorPreview.getInvertedRGB());
    gEdittext.setTextColor(colorPreview.getInvertedRGB());
    bEdittext.setTextColor(colorPreview.getInvertedRGB());
  }

  /**
   * Updates Seekbars with Color Values
   *
   * @param color Color to be set
   */
  private void updateSeekbars(int color) {
    int a = Color.alpha(color);
    int r = Color.red(color);
    int g = Color.green(color);
    int b = Color.blue(color);
    seekAlpha.setProgress(a);
    seekRed.setProgress(r);
    seekGreen.setProgress(g);
    seekBlue.setProgress(b);
  }

  /** Updates EditText with Color Values */
  private void updateEditText() {
    editText.setText(colorPreview.getHexColor());
  }

  /**
   * Called when Seekbar progress is changed
   *
   * @param seek Seekbar which is changed
   * @param progress Progress of Seekbar
   * @param fromUser True if changed by user
   */
  @SuppressLint("NonConstantResourceId")
  @Override
  public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
    if (fromUser) {
      int id = seek.getId();
      if (id == R.id.seek_alpha) {
        colorPreview.setAlpha(progress);
        updateARGB(colorPreview.getColor());
        updateEditText();
      } else if (id == R.id.seek_red) {
        colorPreview.setRed(progress);
        updateARGB(colorPreview.getColor());
        updateEditText();
      } else if (id == R.id.seek_green) {
        colorPreview.setGreen(progress);
        updateARGB(colorPreview.getColor());
        updateEditText();
      } else if (id == R.id.seek_blue) {
        colorPreview.setBlue(progress);
        updateARGB(colorPreview.getColor());
        updateEditText();
      }
    }
  }

  @Override
  public void onStartTrackingTouch(SeekBar p1) {}

  @Override
  public void onStopTrackingTouch(SeekBar p1) {}
}