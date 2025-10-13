# IDE Tooltips Module (`idetooltips`)

## Overview

The `idetooltips` module is responsible for providing a flexible and reusable system for displaying contextual tooltips in CoGo. These tooltips can display short summaries, detailed help content, and external links, all within a floating popup anchored to UI components.

This module provides functionalities such as:
*   Displaying tooltips with rich content (HTML).
*   Fetching tooltip data from a local database.
*   Handling user interactions within tooltips (e.g., "See More" links).

## Features

*   **Dynamic Tooltip Display:** Show tooltips anchored to specific UI elements or coordinates.
*   **Rich Content:** Supports HTML content within tooltips for flexible formatting, including links.
*   **Database Integration:** Fetches tooltip content and metadata from the Room database - `IDETooltipDatabase`.
*   **"See More" Functionality:** Handles expanding content or navigating to detailed documentation.
*   **Custom callbacks:** Customizable action callbacks for link clicks.

### Show a basic tooltip

```kotlin
showIDETooltip(
    context = this,
    anchorView = myButton,
    level = 0,
    tooltipItem = myTooltipItem
)
```
To handle link clicks differently (e.g., log analytics, open custom screens), use the overload:

```kotlin
showIDETooltip(
    context = this,
    anchorView = myButton,
    level = 0,
    tooltipItem = myTooltipItem,
    onHelpLinkClicked = { popupWindow, urlContent ->
        popupWindow.dismiss()
        // Custom logic
        openMyHelpScreen(urlContent.first, urlContent.second)
    }
)
```
