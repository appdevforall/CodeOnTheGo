package com.itsvks.layouteditor.utils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.LayoutFile;
import com.itsvks.layouteditor.R;
import com.itsvks.layouteditor.adapters.models.DrawableFile;
import com.itsvks.layouteditor.adapters.models.FontItem;
import com.itsvks.layouteditor.adapters.models.ValuesItem;

import java.util.List;
import java.util.regex.Pattern;

/**
 * This class is used to check the name error conditions.
 */
public class NameErrorChecker {

  /**
   * This method checks if the name provided by the user is valid.
   *
   * @param name         the name provided by the user
   * @param inputLayout  object of TextInputLayout
   * @param dialog       object of AlertDialog
   * @param drawableList list of DrawableFile objects
   */
  public static void checkForDrawable(
    @NonNull String name,
    TextInputLayout inputLayout,
    AlertDialog dialog,
    List<DrawableFile> drawableList,
    int position) {
    // Check if name is not empty
    if (!name.isEmpty()) {
      // First character should not be a number
      if (Character.isDigit(name.charAt(0))) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_first_letter_not_number));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
      // Name should not have space
      if (name.contains(" ")) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_space_not_allowed));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
      // Name should contain only letters and numbers
      if (!Pattern.matches("[a-z][a-z0-9_]*", name)) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_only_letters_and_numbers));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
    } else {
      // Name cannot be empty
      inputLayout.setErrorEnabled(true);
      inputLayout.setError(dialog.getContext().getString(R.string.msg_cannnot_empty));
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      return;
    }

    // Check if the name already exists in the valuesList
    for (DrawableFile item : drawableList) {
      if (item.getName().substring(0, item.getName().lastIndexOf(".")).equals(name)
        && drawableList.indexOf(item) != position) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_current_name_unavailable));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
    }

    // Name is valid
    inputLayout.setErrorEnabled(false);
    inputLayout.setError("");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
  }

  public static void checkForValues(
    String name, TextInputLayout inputLayout, AlertDialog dialog, List<ValuesItem> valuesList) {
    checkForValues(name, inputLayout, dialog, valuesList, -1);
  }

  public static void checkForFont(
    String name, TextInputLayout inputLayout, AlertDialog dialog, List<FontItem> fontList) {
    checkForFont(name, inputLayout, dialog, fontList, -1);
  }

  public static void checkForDrawable(
    String name, TextInputLayout inputLayout, AlertDialog dialog, List<DrawableFile> drawableList) {
    checkForDrawable(name, inputLayout, dialog, drawableList, -1);
  }

  public static void checkForValues(
    @NonNull String name,
    TextInputLayout inputLayout,
    AlertDialog dialog,
    List<ValuesItem> valuesList,
    int position) {
    // Check if name is not empty
    if (!name.isEmpty()) {
      // First character should not be a number
      if (Character.isDigit(name.charAt(0))) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_first_letter_not_number));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
      // Name should not have space
      if (name.contains(" ")) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_space_not_allowed));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
      // Name should contain only letters and numbers
      if (!Pattern.matches("[a-z][a-z0-9_]*", name)) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_only_letters_and_numbers));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
    } else {
      // Name cannot be empty
      inputLayout.setErrorEnabled(true);
      inputLayout.setError(dialog.getContext().getString(R.string.msg_cannnot_empty));
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      return;
    }

    // Check if the name already exists in the valuesList
    for (ValuesItem item : valuesList) {
      if (item.name.equals(name) && valuesList.indexOf(item) != position) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_current_name_unavailable));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
    }

    // Name is valid
    inputLayout.setErrorEnabled(false);
    inputLayout.setError("");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
  }

  public static void checkForFont(
    @NonNull String name,
    TextInputLayout inputLayout,
    AlertDialog dialog,
    List<FontItem> fontList,
    int position) {
    // Check if name is not empty
    if (!name.isEmpty()) {
      // First character should not be a number
      if (Character.isDigit(name.charAt(0))) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_first_letter_not_number));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
      // Name should not have space
      if (name.contains(" ")) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_space_not_allowed));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
      // Name should contain only letters and numbers
      if (!Pattern.matches("[a-z][a-z0-9_]*", name)) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_only_letters_and_numbers));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
    } else {
      // Name cannot be empty
      inputLayout.setErrorEnabled(true);
      inputLayout.setError(dialog.getContext().getString(R.string.msg_cannnot_empty));
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      return;
    }

    // Check if the name already exists in the fontList
    for (FontItem item : fontList) {
      if (item.name.substring(0, item.name.lastIndexOf(".")).equals(name)
        && fontList.indexOf(item) != position) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_current_name_unavailable));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
    }

    // Name is valid
    inputLayout.setErrorEnabled(false);
    inputLayout.setError("");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
  }

  public static void checkForLayouts(
    @NonNull String name,
    TextInputLayout inputLayout,
    AlertDialog dialog,
    List<LayoutFile> layoutFiles,
    int position) {
    if (!name.isEmpty()) {
      if (Character.isDigit(name.charAt(0))) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_first_letter_not_number));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
      if (!Pattern.matches("[a-z][A-Za-z0-9_\\s]*", name)) {
        inputLayout.setErrorEnabled(true);
        inputLayout.setError(dialog.getContext().getString(R.string.msg_symbol_not_allowed));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return;
      }
    } else {
      inputLayout.setErrorEnabled(true);
      inputLayout.setError(dialog.getContext().getString(R.string.msg_cannnot_empty));
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      return;
    }

    for (LayoutFile item : layoutFiles) {
      if (item.getName().contains(".")) {
        if (item.getName().substring(0, item.getName().lastIndexOf(".")).equals(name)
          && layoutFiles.indexOf(item) != position) {
          inputLayout.setErrorEnabled(true);
          inputLayout.setError(dialog.getContext().getString(R.string.msg_current_name_unavailable));
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
          return;
        }
      }
    }

    inputLayout.setErrorEnabled(false);
    inputLayout.setError("");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
  }
}
