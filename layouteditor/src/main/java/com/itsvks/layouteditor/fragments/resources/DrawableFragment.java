package com.itsvks.layouteditor.fragments.resources;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.blankj.utilcode.util.ToastUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.itsvks.layouteditor.ProjectFile;
import com.itsvks.layouteditor.R;
import com.itsvks.layouteditor.adapters.DPIsListAdapter;
import com.itsvks.layouteditor.adapters.DrawableResourceAdapter;
import com.itsvks.layouteditor.adapters.models.DrawableFile;
import com.itsvks.layouteditor.databinding.DialogSelectDpisBinding;
import com.itsvks.layouteditor.databinding.FragmentResourcesBinding;
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding;
import com.itsvks.layouteditor.managers.ProjectManager;
import com.itsvks.layouteditor.tools.ImageConverter;
import com.itsvks.layouteditor.utils.FileUtil;
import com.itsvks.layouteditor.utils.NameErrorChecker;
import com.itsvks.layouteditor.utils.Utils;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.commons.io.FileUtils;

public class DrawableFragment extends Fragment {

  private FragmentResourcesBinding binding;
  private DrawableResourceAdapter adapter;
  DPIsListAdapter dpiAdapter = null;
  private ProjectFile project;
  private RecyclerView mRecyclerView;
  List<DrawableFile> drawableList = new ArrayList<>();
  private List<String> dpiList;

  public DrawableFragment(List<DrawableFile> drawableList) {
    this.drawableList = drawableList;
    dpiList = new ArrayList<>();
    dpiList.add("ldpi");
    dpiList.add("mdpi");
    dpiList.add("hdpi");
    dpiList.add("xhdpi");
    dpiList.add("xxhdpi");
    dpiList.add("xxxhdpi");
  }

  public DrawableFragment() {}

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentResourcesBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    project = ProjectManager.getInstance().getOpenedProject();
    loadDrawables();
    mRecyclerView = binding.recyclerView;
    // Create the adapter and set it to the RecyclerView
    adapter = new DrawableResourceAdapter(drawableList);
    mRecyclerView.setAdapter(adapter);
    mRecyclerView.setLayoutManager(
        new LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false));
  }

  public void loadDrawables() {
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    Future<Void> future =
        executorService.submit(
            () -> {
              int version = 0;
              File drawableFolder = new File(project.getPath() + "/drawable/");
              if (drawableFolder.exists()) {
                Collection<File> drawables =
                    FileUtils.listFiles(
                        drawableFolder, new String[] {"png", "jpg", "jpeg", "gif", "xml"}, false);
                for (File drawable : drawables) {
                  String drawableName = drawable.getName();
                  Drawable drawableObj =
                      drawableName.endsWith(".xml")
                          ? Utils.getVectorDrawableAsync(requireContext(), Uri.fromFile(drawable))
                          : Drawable.createFromPath(drawable.getPath());
                  for (int i = 0; i < dpiList.size(); i++) {
                    File dpiFolder =
                        new File(project.getPath() + "/drawable-" + dpiList.get(i) + "/");
                    if (dpiFolder.exists()) {
                      File matchingFile = new File(dpiFolder, drawableName);
                      if (matchingFile.exists()) {
                        Drawable matchingDrawableObj =
                            Drawable.createFromPath(matchingFile.getPath());
                        version = i;
                      }
                    }
                  }
                  drawableList.add(new DrawableFile(version + 1, drawableObj, drawable.getPath()));
                }
              }
              return null;
            });

    try {
      future.get();
    } catch (ExecutionException e) {
      // handle exceptions thrown by the loadDrawables() method
    } catch (InterruptedException e) {
      // handle interruptions if necessary
    } finally {
      executorService.shutdown();
    }
  }

  public void addDrawable(Uri uri) {
    final String path = FileUtil.convertUriToFilePath(uri);
    if (TextUtils.isEmpty(path)) {
      ToastUtils.showLong(R.string.invalid_data_intent);
      return;
    }
    // File name with extension
    final String lastSegment = FileUtil.getLastSegmentFromPath(path);

    // File name without extension
    final String fileName = lastSegment.substring(0, lastSegment.lastIndexOf("."));

    // Extension
    final String extension =
        lastSegment.substring(lastSegment.lastIndexOf("."), lastSegment.length());
    final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
    final DialogSelectDpisBinding dialogBinding =
        DialogSelectDpisBinding.inflate(builder.create().getLayoutInflater());
    final TextInputEditText editText = dialogBinding.textinputEdittext;
    final TextInputLayout inputLayout = dialogBinding.textinputLayout;
    inputLayout.setHint(R.string.msg_enter_new_name);
    editText.setText(fileName);

    if (!lastSegment.endsWith(".xml")) {
      dpiAdapter = new DPIsListAdapter(Drawable.createFromPath(path));
      dialogBinding.listDpi.setAdapter(dpiAdapter);
      dialogBinding.listDpi.setLayoutManager(new GridLayoutManager(requireActivity(), 2));
    }
    dialogBinding.listDpi.setVisibility(lastSegment.endsWith(".xml") ? View.GONE : View.VISIBLE);

    builder.setView(dialogBinding.getRoot());
    builder.setTitle(R.string.add_drawable);
    builder.setNegativeButton(R.string.cancel, (di, which) -> {});
    builder.setPositiveButton(
        R.string.add,
        (di, which) -> {
          String drawablePath = project.getDrawablePath();
          int version = 0;
          if (!lastSegment.endsWith(".xml") && dpiAdapter != null) {
            var selectedDPIs = dpiAdapter.getSelectedItems();
            for (int i = 0; i < selectedDPIs.size(); i++) {
              try {
                ImageConverter.convertToDrawableDpis(
                    editText.getText().toString() + extension,
                    BitmapFactory.decodeFile(path),
                    selectedDPIs);
              } catch (IOException e) {
                e.printStackTrace();
              }
              version = i;
            }
          }
          String toPath = drawablePath + editText.getText().toString() + extension;
          FileUtil.copyFile(uri, toPath);

          Drawable drawable =
              lastSegment.endsWith(".xml")
                  ? Utils.getVectorDrawableAsync(requireContext(), Uri.fromFile(new File(toPath)))
                  : Drawable.createFromPath(toPath);
          String name = editText.getText().toString();
          var drawableFile = new DrawableFile(version + 1, drawable, toPath);
          drawableList.add(drawableFile);
          adapter.notifyDataSetChanged();
        });

    final AlertDialog dialog = builder.create();
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    dialog.show();

    editText.addTextChangedListener(
        new TextWatcher() {

          @Override
          public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {}

          @Override
          public void afterTextChanged(Editable p1) {
            NameErrorChecker.checkForDrawable(
                editText.getText().toString(), inputLayout, dialog, drawableList);
          }
        });

    NameErrorChecker.checkForDrawable(fileName, inputLayout, dialog, drawableList);

    editText.requestFocus();
    InputMethodManager inputMethodManager =
        (InputMethodManager)
            dialogBinding.getRoot().getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);

    if (!editText.getText().toString().equals("")) {
      editText.setSelection(0, editText.getText().toString().length());
    }
  }
}
