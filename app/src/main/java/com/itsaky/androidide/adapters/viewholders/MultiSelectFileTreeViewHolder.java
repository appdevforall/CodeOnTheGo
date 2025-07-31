package com.itsaky.androidide.adapters.viewholders;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import com.itsaky.androidide.R;
import com.itsaky.androidide.databinding.LayoutFiletreeItemSelectableBinding;
import com.itsaky.androidide.models.FileExtension;
import com.unnamed.b.atv.model.TreeNode;

import java.io.File;
import java.util.Set;

public class MultiSelectFileTreeViewHolder extends TreeNode.BaseNodeViewHolder<File> {

    private LayoutFiletreeItemSelectableBinding binding;
    private Set<File> selectedFiles;

    public MultiSelectFileTreeViewHolder(Context context, Set<File> selectedFiles) {
        super(context);
        this.selectedFiles = selectedFiles;
    }

    @Override
    public View createNodeView(TreeNode node, File file) {
        binding = LayoutFiletreeItemSelectableBinding.inflate(LayoutInflater.from(context));
        binding.filetreeName.setText(file.getName());
        binding.filetreeIcon.setImageResource(FileExtension.Factory.forFile(file).getIcon());

        int padding = (int) (context.getResources().getDisplayMetrics().density * 16 * (node.getLevel() - 1));
        binding.getRoot().setPadding(padding, binding.getRoot().getPaddingTop(), binding.getRoot().getPaddingRight(), binding.getRoot().getPaddingBottom());

        // --- Expansion and Click Logic ---
        if (file.isDirectory()) {
            binding.filetreeChevron.setVisibility(View.VISIBLE);
            binding.filetreeChevron.setRotation(node.isExpanded() ? 90 : 0);
        } else {
            binding.filetreeChevron.setVisibility(View.INVISIBLE);
        }

        View.OnClickListener toggleListener = v -> {
            if (file.isDirectory()) {
                getTreeView().toggleNode(node);
            }
        };
        binding.filetreeName.setOnClickListener(toggleListener);
        binding.filetreeIcon.setOnClickListener(toggleListener);
        binding.filetreeChevron.setOnClickListener(toggleListener);

        binding.filetreeCheckbox.setOnCheckedChangeListener(null);
        updateCheckboxState(node);
        binding.filetreeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            propagateSelectionToChildren(node, isChecked);
            updateParentCheckboxStates(node.getParent());
        });

        return binding.getRoot();
    }

    @Override
    public void toggle(boolean active) {
        binding.filetreeChevron.setRotation(active ? 90 : 0);
    }

    private void propagateSelectionToChildren(TreeNode node, boolean isSelected) {
        File file = (File) node.getValue();
        if (isSelected) {
            selectedFiles.add(file);
        } else {
            selectedFiles.remove(file);
        }
        updateCheckboxState(node);

        if (file.isDirectory()) {
            for (TreeNode child : node.getChildren()) {
                propagateSelectionToChildren(child, isSelected);
            }
        }
    }

    private void updateParentCheckboxStates(TreeNode parent) {
        if (parent == null || parent.getValue() == null) return;

        MultiSelectFileTreeViewHolder parentViewHolder = (MultiSelectFileTreeViewHolder) parent.getViewHolder();
        if (parentViewHolder != null) {
            parentViewHolder.updateCheckboxState(parent);
        }

        updateParentCheckboxStates(parent.getParent());
    }

    public void updateCheckboxState(TreeNode node) {
        File file = node.getValue();
        if (file == null) return;

        binding.filetreeCheckbox.setOnCheckedChangeListener(null);

        if (file.isDirectory()) {
            int childCount = node.getChildren().size();
            if (childCount == 0) {
                binding.filetreeCheckbox.setButtonDrawable(R.drawable.abc_btn_check_material);
                binding.filetreeCheckbox.setChecked(selectedFiles.contains(file));
            } else {
                int selectedCount = 0;
                for (TreeNode child : node.getChildren()) {
                    if (selectedFiles.contains(child.getValue())) {
                        selectedCount++;
                    }
                }

                if (selectedCount == 0) {
                    binding.filetreeCheckbox.setButtonDrawable(R.drawable.abc_btn_check_material);
                    binding.filetreeCheckbox.setChecked(false);
                    selectedFiles.remove(file);
                } else if (selectedCount == childCount) {
                    binding.filetreeCheckbox.setButtonDrawable(R.drawable.abc_btn_check_material);
                    binding.filetreeCheckbox.setChecked(true);
                    selectedFiles.add(file);
                } else {
                    binding.filetreeCheckbox.setButtonDrawable(R.drawable.ic_indeterminate_check_box);
                    binding.filetreeCheckbox.setChecked(true);
                    selectedFiles.remove(file);
                }
            }
        } else {
            binding.filetreeCheckbox.setButtonDrawable(R.drawable.abc_btn_check_material);
            binding.filetreeCheckbox.setChecked(selectedFiles.contains(file));
        }

        binding.filetreeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            propagateSelectionToChildren(node, isChecked);
            updateParentCheckboxStates(node.getParent());
        });
    }
}