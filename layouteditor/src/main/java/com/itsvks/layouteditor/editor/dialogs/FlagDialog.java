package com.itsvks.layouteditor.editor.dialogs;

import android.content.Context;

import android.util.SparseBooleanArray;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.itsvks.layouteditor.editor.dialogs.AttributeDialog;
import java.util.ArrayList;
import java.util.List;

public class FlagDialog extends AttributeDialog {

  // Declaring a ListView object
  private ListView listview;

  // Declaring a List object to store arguments
  private List<String> arguments;

  /**
   * Constructor to initialize the FlagDialog object
   *
   * @param context The context of the calling activity
   * @param savedValue The savedValue of the calling activity
   * @param arguments The list of arguments
   */
  public FlagDialog(Context context, @NonNull String savedValue, ArrayList<String> arguments) {
    super(context);

    // Assigning arguments to the List object
    this.arguments = arguments;

    // Initializing listview
    listview = new ListView(context);

    // Setting the layout params
    listview.setLayoutParams(
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    // Setting multiple choice mode for listview
    listview.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

    // Setting adapter to listview
    listview.setAdapter(
        new ArrayAdapter<String>(
            context, android.R.layout.simple_list_item_multiple_choice, arguments));

    // Setting divider to null
    listview.setDivider(null);

    // Checking if the savedValue is not empty
    if (!savedValue.isEmpty()) {
      // Splitting the savedValue using | as the delimiter
      String[] flags = savedValue.split("\\|");

      // Looping through the flags array
      for (String flag : flags) {
        // Getting the index of the flag in the arguments list
        int index = arguments.indexOf(flag);

        // Setting the listview item at the index as checked
        listview.setItemChecked(index, true);
      }
    }

    // Setting padding around the listview
    setView(listview, 0, 20, 0, 0);
  }

  /** Overriding the superclass's method to save the data */
  @Override
  protected void onClickSave() {
    // Calling the superclass's method
    super.onClickSave();

    // Checking if no item is checked in the listview
    if (listview.getCheckedItemCount() == 0) {
      // Passing -1 as the saved value
      listener.onSave("-1");

      // Returning
      return;
    }

    // Creating a StringBuilder object
    StringBuilder builder = new StringBuilder();

    // Getting the checked item positions
    SparseBooleanArray array = listview.getCheckedItemPositions();

    // Looping through the array
    for (int i = 0; i < array.size(); i++) {
      // Getting the checked item
      int checkedItem = array.keyAt(i);

      // Checking if the item is checked
      if (array.get(checkedItem)) {
        // Appending the checked item's argument in the arguments list to the StringBuilder object
        builder.append(arguments.get(checkedItem)).append("|");
      }
    }

    // Converting the StringBuilder object to String object
    String value = builder.toString();

    // Removing the last | from the String object
    value = value.substring(0, value.lastIndexOf("|"));

    // Passing the String object as the saved value
    listener.onSave(value);
  }
}
