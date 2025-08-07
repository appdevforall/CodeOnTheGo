package com.itsvks.layouteditor.editor.dialogs;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class EnumDialog extends AttributeDialog {

  private ListView listview;
  private List<String> arguments;

  /**
   * Constructor for EnumDialog class
   *
   * @param context the context of the activity
   * @param savedValue the saved value of the parameter
   * @param arguments the list of arguments in the parameter
   */
  public EnumDialog(Context context, @NonNull String savedValue, ArrayList<String> arguments) {
    super(context);

    this.arguments = arguments;

    listview = new ListView(context);
    // Set the view layout parameters
    listview.setLayoutParams(
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    // Set adapter
    listview.setAdapter(
        new ArrayAdapter<String>(
            context, android.R.layout.simple_list_item_single_choice, arguments));
    // Set choice mode
    listview.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
    // Remove divider
    listview.setDivider(null);

    if (!savedValue.isEmpty()) {
      listview.setItemChecked(arguments.indexOf(savedValue), true);
    }

    // Set view, and pass in padding arguments
    setView(listview, 0, 20, 0, 0);
  }

  /**
   * Overridden method from AttributeDialog class
   * Gets the listview checked item position, saves it
   * and passes the saved value to the listener
   */
  @Override
  protected void onClickSave() {
    if (listview.getCheckedItemPosition() == -1) {
      listener.onSave("-1");
      return;
    }

    listener.onSave(arguments.get(listview.getCheckedItemPosition()));
  }
}
