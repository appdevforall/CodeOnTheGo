package com.itsaky.androidide.agent.model

import androidx.annotation.DrawableRes

// A sealed interface to represent any item in our list
sealed interface ContextListItem

// A data class for section titles like "FILES AND FOLDERS"
data class HeaderItem(val title: String) : ContextListItem

// A data class for a single selectable option
data class SelectableItem(
    val id: String, // A unique ID for the item
    val text: String,
    @DrawableRes val icon: Int,
    var isSelected: Boolean = false
) : ContextListItem