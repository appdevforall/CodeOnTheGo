package com.itsvks.layouteditor.fragments.resources;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.blankj.utilcode.util.ToastUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.ProjectFile;
import com.itsvks.layouteditor.adapters.FontResourceAdapter;
import com.itsvks.layouteditor.adapters.models.FontItem;
import com.itsvks.layouteditor.databinding.FragmentResourcesBinding;
import com.itsvks.layouteditor.databinding.LayoutFontItemDialogBinding;
import com.itsvks.layouteditor.managers.ProjectManager;
import com.itsvks.layouteditor.utils.FileUtil;
import com.itsvks.layouteditor.R;
import com.itsvks.layouteditor.utils.NameErrorChecker;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FontFragment extends Fragment {

  private FragmentResourcesBinding binding;
  private FontResourceAdapter adapter;
  private ProjectFile project;
  private List<FontItem> fontList = new ArrayList<>();

  @Override
  public android.view.View onCreateView(
    @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentResourcesBinding.inflate(inflater, container, false);
    project = ProjectManager.getInstance().getOpenedProject();
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    loadFonts();
    RecyclerView mRecyclerView = binding.recyclerView;
    adapter = new FontResourceAdapter(fontList);
    mRecyclerView.setAdapter(adapter);
    mRecyclerView.setLayoutManager(
        new LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false));
  }

  private void loadFonts() {
    File[] files = project.getFonts();

    if (files == null) {
      ToastUtils.showShort("Null");
    } else {
      for (File file : files) {
        String name = file.getName();
        // name = name.substring(0, name.lastIndexOf("."));
        fontList.add(new FontItem(name, file.getPath()));
      }
    }
  }

  public void addFont(final Uri uri) {
    String path = FileUtil.convertUriToFilePath(uri);
    if (TextUtils.isEmpty(path)) {
      ToastUtils.showLong(R.string.invalid_data_intent);
      return;
    }
    final String lastSegment = FileUtil.getLastSegmentFromPath(path);
    final String fileName = lastSegment.substring(0, lastSegment.lastIndexOf("."));
    final String extension =
        lastSegment.substring(lastSegment.lastIndexOf("."));
    final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
    final LayoutFontItemDialogBinding dialogBinding =
        LayoutFontItemDialogBinding.inflate(builder.create().getLayoutInflater());
    final TextInputEditText editTextName = dialogBinding.textinputName;
    final TextInputLayout inputLayoutName = dialogBinding.textInputLayoutName;
    inputLayoutName.setHint(R.string.msg_enter_new_name);
    editTextName.setText(fileName);

    builder.setView(dialogBinding.getRoot());
    builder.setTitle(R.string.add_font);
    builder.setNegativeButton(R.string.cancel, (di, which) -> {});
    builder.setPositiveButton(
        R.string.add,
        (di, which) -> {
          String fontPath = project.getFontPath();

          String toPath = fontPath + editTextName.getText().toString() + extension;
          FileUtil.copyFile(uri, toPath);

          String name = editTextName.getText().toString();
          var fontItem = new FontItem(name + extension, toPath);
          fontList.add(fontItem);
          adapter.notifyItemInserted(fontList.indexOf(fontItem));
        });

    final AlertDialog dialog = builder.create();
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    dialog.show();

    editTextName.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void afterTextChanged(Editable p1) {
            NameErrorChecker.checkForFont(
                editTextName.getText().toString(), inputLayoutName, dialog, fontList);
          }
        });

    NameErrorChecker.checkForFont(fileName, inputLayoutName, dialog, fontList);

    editTextName.requestFocus();
    InputMethodManager inputMethodManager =
        (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    inputMethodManager.showSoftInput(editTextName, InputMethodManager.SHOW_IMPLICIT);

    if (!editTextName.getText().toString().isEmpty()) {
      editTextName.setSelection(0, editTextName.getText().toString().length());
    }
  }
}
