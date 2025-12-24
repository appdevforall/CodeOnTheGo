package org.appdevforall.codeonthego.layouteditor.fragments.resources;

import android.annotation.SuppressLint;
import android.graphics.Color;
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
import com.skydoves.colorpickerview.ColorPickerDialog;
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener;

import org.appdevforall.codeonthego.layouteditor.ProjectFile;
import org.appdevforall.codeonthego.layouteditor.R;
import org.appdevforall.codeonthego.layouteditor.adapters.ColorResourceAdapter;
import org.appdevforall.codeonthego.layouteditor.adapters.models.ValuesItem;
import org.appdevforall.codeonthego.layouteditor.databinding.FragmentResourcesBinding;
import org.appdevforall.codeonthego.layouteditor.databinding.LayoutValuesItemDialogBinding;
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager;
import org.appdevforall.codeonthego.layouteditor.tools.ColorPickerDialogFlag;
import org.appdevforall.codeonthego.layouteditor.tools.ValuesResourceParser;
import org.appdevforall.codeonthego.layouteditor.utils.NameErrorChecker;
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    private void setupDialogViews(LayoutValuesItemDialogBinding bind) {
        TextInputEditText etValue = bind.textinputValue;
        etValue.setFocusable(true);
        etValue.setFocusableInTouchMode(true);
        etValue.setOnClickListener(null);
    }

    private void setupColorPicker(TextInputLayout ilValue, TextInputEditText etValue) {
        ilValue.setEndIconOnClickListener(v -> showColorPickerDialog(etValue, ilValue));
    }

    private void setupInputValidation(AlertDialog dialog, LayoutValuesItemDialogBinding bind) {
        TextWatcher validator = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                validateInputs(dialog, bind);
            }
        };

        bind.textinputName.addTextChangedListener(validator);
        bind.textinputValue.addTextChangedListener(validator);
    }

    private void validateInputs(AlertDialog dialog, LayoutValuesItemDialogBinding bind) {
        String name = Objects.requireNonNull(bind.textinputName.getText()).toString();
        String value = Objects.requireNonNull(bind.textinputValue.getText()).toString();

        NameErrorChecker.checkForValues(name, bind.textInputLayoutName, dialog, colorList);
        boolean isNameValid = bind.textInputLayoutName.getError() == null && !name.trim().isEmpty();

        boolean isColorValid = checkColorValidity(value, bind.textInputLayoutValue);

        boolean isFormValid = isNameValid && isColorValid;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(isFormValid);
    }

    private boolean checkColorValidity(String colorHex, TextInputLayout ilValue) {
        if (colorHex.trim().isEmpty()) {
            ilValue.setError(null);
            return false;
        }

        try {
            getSafeColor(colorHex);
            ilValue.setError(null);
            ilValue.setErrorEnabled(false);
            return true;
        } catch (IllegalArgumentException e) {
            ilValue.setError("Invalid color");
            return false;
        }
    }

    private void setupSaveButton(AlertDialog dialog, LayoutValuesItemDialogBinding bind) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> handleSaveColor(dialog, bind));
    }

    private void handleSaveColor(AlertDialog dialog, LayoutValuesItemDialogBinding bind) {
        String name = Objects.requireNonNull(bind.textinputName.getText()).toString().trim();
        String value = Objects.requireNonNull(bind.textinputValue.getText()).toString().trim();

        if (name.isEmpty()) {
            bind.textInputLayoutName.setError("Name required");
            return;
        }

        try {
            String finalValue = getSafeColor(value);
            bind.textInputLayoutValue.setError(null);

            ValuesItem colorItem = new ValuesItem(name, finalValue);
            colorList.add(colorItem);
            adapter.notifyItemInserted(colorList.indexOf(colorItem));
            adapter.generateColorsXml();

            dialog.dismiss();
        } catch (IllegalArgumentException e) {
            bind.textInputLayoutValue.setError("Invalid color (e.g. #FF0000)");
        }
    }

    public void addColor() {
        LayoutValuesItemDialogBinding dialogBinding = LayoutValuesItemDialogBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Color")
            .setView(dialogBinding.getRoot())
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create();

        setupDialogViews(dialogBinding);
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

        setupColorPicker(dialogBinding.textInputLayoutValue, dialogBinding.textinputValue);
        setupInputValidation(dialog, dialogBinding);
        setupSaveButton(dialog, dialogBinding);
    }

    private void showColorPickerDialog(TextInputEditText etValue, TextInputLayout ilValue) {
        @SuppressLint("SetTextI18n")
        ColorPickerDialog.Builder builder = new ColorPickerDialog.Builder(requireContext())
                .setTitle("Choose Color")
                .setPositiveButton(getString(R.string.confirm), (ColorEnvelopeListener) (envelope, fromUser) -> {
                    etValue.setText("#" + envelope.getHexCode());
                    ilValue.setError(null);
                })
                .setNegativeButton(getString(R.string.cancel), (d, i) -> d.dismiss())
                .attachAlphaSlideBar(true)
                .attachBrightnessSlideBar(true)
                .setBottomSpace(12);

        var colorView = builder.getColorPickerView();
        colorView.setFlagView(new ColorPickerDialogFlag(requireContext()));

        // Try to set initial color safely
        try {
            String colorStr = Objects.requireNonNull(etValue.getText()).toString();
            if (!colorStr.isEmpty()) {
                colorView.setInitialColor(Color.parseColor(getSafeColor(colorStr)));
            }
        } catch (Exception ignored) {}

        builder.show();
    }

    /**
     * Attempts to parse an input string and return a safe hexadecimal color string.
     * It first tries to parse the [input] as is. If that fails, it checks if the "#" prefix
     * is missing. If missing, it appends it and tries to parse again.
     *
     * @param input The raw color string (e.g., "FFFFFF" or "#FFFFFF").
     * @return The valid color string (including the "#" prefix if it was needed).
     * @throws IllegalArgumentException If the input cannot be parsed as a valid color even after correction.
     */
    private String getSafeColor(String input) throws IllegalArgumentException {
        try {
            Color.parseColor(input);
            return input;
        } catch (IllegalArgumentException ignored) {}

        String fixed = hexPrefixValidation(input);
        if (fixed != null) return fixed;

        throw new IllegalArgumentException("Unknown color");
    }

    @Nullable
    private static String hexPrefixValidation(String input) {
        if (!input.startsWith("#")) {
            String fixed = "#" + input;
            try {
                Color.parseColor(fixed);
                return fixed;
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }
}
