package com.itsvks.layouteditor.editor.dialogs;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.itsvks.layouteditor.editor.dialogs.interfaces.OnSaveValueListener;

public class AttributeDialog {

  private final AlertDialog dialog;
  protected OnSaveValueListener listener;

  private InputMethodManager inputMethodManager;

  /**
   * Constructor for AttributeDialog
   *
   * @param context Application context
   */
  public AttributeDialog(Context context) {

    dialog = new MaterialAlertDialogBuilder(context).create();
    dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (di, which) -> {});
    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (di, which) -> onClickSave());

    inputMethodManager =
        (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
  }

  /**
   * Set title of the dialog
   *
   * @param title Title of the dialog
   */
  public void setTitle(String title) {
    dialog.setTitle(title);
  }

  /**
   * Set view of the dialog
   *
   * @param view View of the dialog
   */
  public void setView(View view) {
    dialog.setView(view);
  }

  /**
   * Set view of the dialog with padding
   *
   * @param view View of the dialog
   * @param padding Padding for the view
   */
  public void setView(@NonNull View view, int padding) {
    int pad = getDip(view.getContext(), padding);
    dialog.setView(view, pad, pad, pad, pad);
  }

  /**
   * Set view of the dialog with padding
   *
   * @param view View of the dialog
   * @param left Left padding for the view
   * @param top Top padding for the view
   * @param right Right padding for the view
   * @param bottom Bottom padding for the view
   */
  public void setView(View view, int left, int top, int right, int bottom) {
    dialog.setView(
        view,
        getDip(view.getContext(), left),
        getDip(view.getContext(), top),
        getDip(view.getContext(), right),
        getDip(view.getContext(), bottom));
  }

  /** Show the dialog */
  public void show() {
    dialog.show();
  }

  /**
   * Set enabled state of the positive button
   *
   * @param enabled Enabled state of the positive button
   */
  public void setEnabled(boolean enabled) {
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
  }

  /** Show Keyboard when open */
  protected void showKeyboardWhenOpen() {
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
  }

  /**
   * Request EditText for focus
   *
   * @param editText TextInputEditText for focus
   */
  protected void requestEditText(@NonNull TextInputEditText editText) {
    editText.requestFocus();
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);

    if (!editText.getText().toString().isEmpty()) {
      editText.setSelection(0, editText.getText().toString().length());
    }
  }

  /**
   * Get dip for the value
   *
   * @param context Application context
   * @param value Value for which dip is required
   * @return Dip value
   */
  private int getDip(@NonNull Context context, int value) {
    return (int)
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
  }

  /**
   * Get string from resource
   *
   * @param context Application context
   * @param id Resource id
   * @return String value
   */
  protected String getString(@NonNull Context context, int id) {
    return context.getString(id);
  }

  /**
   * Set OnSaveValueListener
   *
   * @param listener OnSaveValueListener
   */
  public void setOnSaveValueListener(OnSaveValueListener listener) {
    this.listener = listener;
  }

  /** Called on clicking save */
  protected void onClickSave() {}

  /**
   * Getter for dialog
   *
   * @return AlertDialog
   */
  public AlertDialog getDialog() {
    return this.dialog;
  }
}
