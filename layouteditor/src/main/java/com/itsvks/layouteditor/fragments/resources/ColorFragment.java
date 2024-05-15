package com.itsvks.layouteditor.fragments.resources;

import android.annotation.SuppressLint;
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
import com.itsvks.layouteditor.adapters.ColorResourceAdapter;
import com.itsvks.layouteditor.adapters.models.ValuesItem;
import com.itsvks.layouteditor.databinding.FragmentResourcesBinding;
import com.itsvks.layouteditor.databinding.LayoutValuesItemDialogBinding;
import com.itsvks.layouteditor.managers.ProjectManager;
import com.itsvks.layouteditor.tools.ColorPickerDialogFlag;
import com.itsvks.layouteditor.tools.ValuesResourceParser;
import com.itsvks.layouteditor.utils.NameErrorChecker;
import com.itsvks.layouteditor.utils.SBUtils;
import com.skydoves.colorpickerview.ColorPickerDialog;
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @authors: @raredeveloperofc and @itsvks19;
 */
public class ColorFragment extends Fragment {

  private FragmentResourcesBinding binding;
  private ColorResourceAdapter adapter;
  private List<ValuesItem> colorList = new ArrayList<>();
  ValuesResourceParser colorParser;

  @Override
  public android.view.View onCreateView(
    @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentResourcesBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    ProjectFile project = ProjectManager.getInstance().getOpenedProject();
    try {
      loadColorsFromXML(project.getColorsPath());
    } catch (FileNotFoundException e) {
      SBUtils.make(view, "An error occurred: " + e.getMessage())
        .setFadeAnimation()
        .setType(SBUtils.Type.INFO)
        .show();
    }
    RecyclerView mRecyclerView = binding.recyclerView;
    adapter = new ColorResourceAdapter(project, colorList);
    mRecyclerView.setAdapter(adapter);
    mRecyclerView.setLayoutManager(
      new LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false));
  }

  /**
   * @param filePath = Current project colors file path;
   */
  public void loadColorsFromXML(String filePath) throws FileNotFoundException {
    InputStream stream = new FileInputStream(filePath);
    colorParser = new ValuesResourceParser(stream, ValuesResourceParser.TAG_COLOR);
    colorList = colorParser.getValuesList();
  }

  public void addColor() {
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
    builder.setTitle("New Color");

    LayoutValuesItemDialogBinding bind = LayoutValuesItemDialogBinding.inflate(getLayoutInflater());
    TextInputLayout ilName = bind.textInputLayoutName;
    TextInputLayout ilValue = bind.textInputLayoutValue;
    TextInputEditText etName = bind.textinputName;
    TextInputEditText etValue = bind.textinputValue;

    etValue.setFocusable(false);
    etValue.setOnClickListener(
      (v) -> {
        @SuppressLint("SetTextI18n")
        var dialog = new ColorPickerDialog.Builder(requireContext())
          .setTitle("Choose Color")
          .setPositiveButton(getString(R.string.confirm),
            (ColorEnvelopeListener) (envelope, fromUser) -> {
              etValue.setText("#" + envelope.getHexCode());
            })
          .setNegativeButton(getString(R.string.cancel),
            (d, i) -> d.dismiss())
          .attachAlphaSlideBar(true)
          .attachBrightnessSlideBar(true)
          .setBottomSpace(12);

        var colorView = dialog.getColorPickerView();
        colorView.setFlagView(new ColorPickerDialogFlag(requireContext()));
        dialog.show();
      });
    builder.setView(bind.getRoot());

    builder.setPositiveButton(
      R.string.add,
      (dlg, i) -> {
        // Create new ColorItem(ValuesItem) instance
        var colorItem = new ValuesItem(etName.getText().toString(), etValue.getText().toString());
        // Add colorItem in stringList
        colorList.add(colorItem);
        adapter.notifyItemInserted(colorList.indexOf(colorItem));
        // Generate code from all colors in list
        adapter.generateColorsXml();
      });
    builder.setNegativeButton(R.string.cancel, null);

    AlertDialog dialog = builder.create();
    dialog.show();

    etName.addTextChangedListener(
      new TextWatcher() {

        @Override
        public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {
        }

        @Override
        public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {
        }

        @Override
        public void afterTextChanged(Editable p1) {
          NameErrorChecker.checkForValues(etName.getText().toString(), ilName, dialog, colorList);
        }
      });
    NameErrorChecker.checkForValues(etName.getText().toString(), ilName, dialog, colorList);
  }
}
