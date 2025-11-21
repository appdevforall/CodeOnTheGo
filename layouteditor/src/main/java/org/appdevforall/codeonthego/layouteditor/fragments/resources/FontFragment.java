package org.appdevforall.codeonthego.layouteditor.fragments.resources;

import static org.appdevforall.codeonthego.layouteditor.utils.Utils.isValidFontFile;

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
import org.appdevforall.codeonthego.layouteditor.ProjectFile;
import org.appdevforall.codeonthego.layouteditor.adapters.FontResourceAdapter;
import org.appdevforall.codeonthego.layouteditor.adapters.models.FontItem;
import org.appdevforall.codeonthego.layouteditor.databinding.FragmentResourcesBinding;
import org.appdevforall.codeonthego.layouteditor.databinding.LayoutFontItemDialogBinding;
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager;
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil;
import org.appdevforall.codeonthego.layouteditor.R;
import org.appdevforall.codeonthego.layouteditor.utils.NameErrorChecker;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FontFragment extends Fragment {

  private FragmentResourcesBinding binding;
  private FontResourceAdapter adapter;
  private ProjectFile project;
  private List<FontItem> fontList = new ArrayList<>();
  private static final ExecutorService executor = Executors.newSingleThreadExecutor();

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
    executor.execute(() -> {
      File[] files = project.getFonts();

      if (files == null) {
        requireActivity().runOnUiThread(() -> ToastUtils.showShort("Null"));
        return;
      }

      List<FontItem> temp = new ArrayList<>();
      for (File file : files) {
        String name = file.getName();

        if (!isValidFontFile(file)) {
          requireActivity().runOnUiThread(() ->
            ToastUtils.showLong(getString(R.string.msg_font_load_invalid, name))
          );
          continue;
        }
        temp.add(new FontItem(name, file.getPath()));
      }

      requireActivity().runOnUiThread(() -> {
        fontList.clear();
        fontList.addAll(temp);
        if (adapter != null) adapter.notifyDataSetChanged();
      });
    });
  }

  public void addFont(final Uri uri) {
    String path = FileUtil.convertUriToFilePath(this.getContext(),uri);
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
          executor.execute(() -> {
            String fontPath = project.getFontPath();
            String toPath = fontPath + editTextName.getText().toString() + extension;
            String name = editTextName.getText().toString();

            FileUtil.copyFile(uri, toPath, getContext());
            boolean valid = isValidFontFile(new File(toPath));

            requireActivity().runOnUiThread(() -> {
              if (!valid) {
                ToastUtils.showLong(getString(R.string.msg_font_add_invalid));
                new File(toPath).delete();
                return;
              }

              FontItem fontItem = new FontItem(name + extension, toPath);
              fontList.add(fontItem);
              adapter.notifyItemInserted(fontList.indexOf(fontItem));
            });
          });
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
