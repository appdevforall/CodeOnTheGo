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

        // Allow user to type the hex code.
        etValue.setFocusable(true);
        etValue.setFocusableInTouchMode(true);
        etValue.setOnClickListener(null);

        // Set a click listener on the end icon (suffix) to bring up the color picker.
        ilValue.setEndIconOnClickListener(v -> {
            @SuppressLint("SetTextI18n")
            var dialog = new ColorPickerDialog.Builder(requireContext())
                    .setTitle("Choose Color")
                    .setPositiveButton(getString(R.string.confirm),
                            (ColorEnvelopeListener) (envelope, fromUser) -> {
                                etValue.setText("#" + envelope.getHexCode());
                                ilValue.setError(null);
                            })
                    .setNegativeButton(getString(R.string.cancel),
                            (d, i) -> d.dismiss())
                    .attachAlphaSlideBar(true)
                    .attachBrightnessSlideBar(true)
                    .setBottomSpace(12);
            var colorView = dialog.getColorPickerView();
            colorView.setFlagView(new ColorPickerDialogFlag(requireContext()));
            try {
              colorView.setInitialColor(Color.parseColor(etValue.getText().toString()));
            } catch (Exception ignored) {}
            dialog.show();
        });

        builder.setView(bind.getRoot());

        builder.setPositiveButton(R.string.add, null);
        builder.setNegativeButton(R.string.cancel, null);

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Editable editableName = etName.getText();
            Editable editableValue = etValue.getText();
            String name = editableName != null ? editableName.toString().trim() : "";
            String value = editableValue != null ? editableValue.toString().trim() : "";

            if (name.isEmpty()) {
                ilName.setError("Name required");
                return;
            }

            try {
                String finalValue = getSafeColor(value);

                ilValue.setError(null);

                var colorItem = new ValuesItem(name, finalValue);
                colorList.add(colorItem);
                adapter.notifyItemInserted(colorList.indexOf(colorItem));
                adapter.generateColorsXml();

                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                ilValue.setError("Invalid color (e.g. #FF0000)");
            }
        });

        etName.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                Editable editable = etName.getText();
                String name = editable != null ? editable.toString() : "";
                NameErrorChecker.checkForValues(name, ilName, dialog, colorList);
                validateColorAndSyncButton(dialog, etValue, ilValue);
            }
        });
        etValue.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                Editable editable = etName.getText();
                String name = editable != null ? editable.toString() : "";
                NameErrorChecker.checkForValues(name, ilName, dialog, colorList);
                validateColorAndSyncButton(dialog, etValue, ilValue);
            }
        });
    }

    /**
     * Validates the input text as a color and synchronizes the dialog's positive button state.
     * This method checks if the text in [etValue] is a valid color (hexadecimal).
     * If invalid, it sets an error on [ilValue] and disables the positive button of the [dialog].
     * If the field is empty, it clears errors but treats the state as invalid.
     *
     * @param dialog The AlertDialog containing the button to synchronize.
     * @param etValue The TextInputEditText containing the color string to validate.
     * @param ilValue The TextInputLayout used to display visual error messages.
     */
    private void validateColorAndSyncButton(AlertDialog dialog, TextInputEditText etValue, TextInputLayout ilValue) {
        Editable editable = etValue.getText();
        String color = editable != null ? editable.toString() : "";
        String colorHex = color.trim();
        boolean isColorValid;

        if (colorHex.isEmpty()) {
            ilValue.setError(null);
            ilValue.setErrorEnabled(false);
            isColorValid = false;
        } else {
            try {
                getSafeColor(colorHex);
                ilValue.setError(null);
                ilValue.setErrorEnabled(false);
                isColorValid = true;
            } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
                ilValue.setError("Invalid color");
                isColorValid = false;
            }
        }

        if (!isColorValid) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        }
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
        } catch (IllegalArgumentException e) {
            if (!input.startsWith("#")) {
                String fixed = "#" + input;
                Color.parseColor(fixed);
                return fixed;
            }
            throw e;
        }
    }
}
