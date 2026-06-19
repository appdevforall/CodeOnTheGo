# IDE Tooltips Module (`idetooltips`)

## Overview

The `idetooltips` module is responsible for providing a flexible and reusable system for displaying contextual tooltips in Code On the Go. These tooltips can display short summaries, detailed help content, and external links, all within a floating popup anchored to UI components.

This module provides functionalities such as:
*   Displaying tooltips with rich content (HTML).
*   Fetching tooltip data from a local database.
*   Handling user interactions within tooltips (e.g., "See More" links).

## Design principle: long-press-for-help is everywhere

Help in Code On The Go is reached the same way no matter where you are: **long-press**. This is a product promise, not a per-screen decision — a user who long-presses to learn what something does should never be met with silence.

Concretely, that means:

*   **Every interactive element provides help** — buttons, icon-only controls, menu items, list rows, editor-toolbar actions. If a view does something when tapped, long-pressing it must surface help.
*   **Every major screen or panel is covered too.** Even where an individual pixel isn't itself interactive, its containing surface (screen, panel, dialog) must offer at least a top-level help entry, so help is always reachable from anywhere in that surface.

### The three-tier help system

A single long-press opens a progressive, three-tier help experience:

*   **Tier 1 & Tier 2 — tooltips.** Rendered by this module as anchored popups (the `level` argument to `showIDETooltip` selects the depth — a short summary first, then more detailed help). These stay in-context and don't leave the screen.
*   **Tier 3 — a full help web page.** Reached from a tooltip's "See More" / help link, this opens proper standalone documentation for users who need the complete explanation.

> Tooltip *content* may be incomplete while help is still being authored, but the *affordance* must exist: new UI ships with long-press help wired up. See `REVIEW.md` (Contextual help) for the review-time rule.

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
