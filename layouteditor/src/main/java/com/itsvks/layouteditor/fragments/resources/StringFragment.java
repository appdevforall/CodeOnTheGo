package com.itsvks.layouteditor.fragments.resources;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.ProjectFile;
import com.itsvks.layouteditor.R;
import com.itsvks.layouteditor.adapters.StringResourceAdapter;
import com.itsvks.layouteditor.adapters.models.ValuesItem;
import com.itsvks.layouteditor.databinding.FragmentResourcesBinding;
import com.itsvks.layouteditor.databinding.LayoutValuesItemDialogBinding;
import com.itsvks.layouteditor.managers.ProjectManager;
import com.itsvks.layouteditor.tools.ValuesResourceParser;
import com.itsvks.layouteditor.utils.NameErrorChecker;
import com.itsvks.layouteditor.utils.SBUtils;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/*
 * @authors: @raredeveloperofc and @itsvks19;
 */
public class StringFragment extends Fragment {
  private FragmentResourcesBinding binding;
  private StringResourceAdapter adapter;
  private RecyclerView mRecyclerView;
  private List<ValuesItem> stringList = new ArrayList<>();
  ValuesResourceParser stringParser;

  @Override
  public android.view.View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentResourcesBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    ProjectFile project = ProjectManager.getInstance().getOpenedProject();
    try {
      loadStringsFromXML(project.getStringsPath());
    } catch (FileNotFoundException e) {
      SBUtils.make(view, "An error occured: " + e.getMessage())
          .setFadeAnimation()
          .setType(SBUtils.Type.INFO)
          .show();
    }
    mRecyclerView = binding.recyclerView;
    adapter = new StringResourceAdapter(project, stringList);
    mRecyclerView.setAdapter(adapter);
    mRecyclerView.setLayoutManager(
        new LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false));
  }

  /*
   * @param filePath = Current project strings file path;
   */
  public void loadStringsFromXML(String filePath) throws FileNotFoundException {
    InputStream stream = new FileInputStream(filePath);
    stringParser = new ValuesResourceParser(stream, ValuesResourceParser.TAG_STRING);
    stringList = stringParser.getValuesList();
  }

  public void addString() {
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
    builder.setTitle("New String");

    LayoutValuesItemDialogBinding bind = LayoutValuesItemDialogBinding.inflate(getLayoutInflater());
    TextInputLayout ilName = bind.textInputLayoutName;
    TextInputLayout ilValue = bind.textInputLayoutValue;
    TextInputEditText etName = bind.textinputName;
    TextInputEditText etValue = bind.textinputValue;

    builder.setView(bind.getRoot());

    builder.setPositiveButton(
        R.string.add,
        (dlg, i) -> {
          // Create new StringItem(ValuesItem) instance
          var stringItem =
              new ValuesItem(etName.getText().toString(), etValue.getText().toString());
          // Add stringItem in stringList
          stringList.add(stringItem);
          adapter.notifyItemInserted(stringList.indexOf(stringItem));
          // Generate code from all strings in list
          adapter.generateStringsXml();
        });
    builder.setNegativeButton(R.string.cancel, null);

    AlertDialog dialog = builder.create();
    dialog.show();

    etName.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void afterTextChanged(Editable p1) {
            NameErrorChecker.checkForValues(
                etName.getText().toString(), ilName, dialog, stringList);
          }
        });
    NameErrorChecker.checkForValues(etName.getText().toString(), ilName, dialog, stringList);
  }
}
