# Hackathon Presentation - Usage Guide

## Overview

HTML-based technical presentation for Local LLM Hackathon showcasing:
- 🎤 Speech-to-Code (WhatsApp-style voice coding)
- ✨ Inline Suggestions (Copilot-style completions)
- 🔍 Vector Search (Semantic code search)

## Usage

### Opening the Presentation

1. Open `hackathon-presentation.html` in any modern browser
2. Presentation starts on slide 1/18

### Navigation

- **Next Slide:** Right Arrow, Space
- **Previous Slide:** Left Arrow
- **First Slide:** Home
- **Last Slide:** End

### Features

- 18 slides with technical deep dive content
- Dark theme optimized for presentations
- Mermaid.js diagrams for architecture visualization
- Responsive design (1024px+)
- Keyboard-only navigation

## Slides Breakdown

1. Title
2. Overview (stats and feature list)
3. Feature grid (3 pillars)
4. Unified architecture diagram
5. Speech-to-Code: Overview
6. Speech-to-Code: Architecture
7. Speech-to-Code: Pipeline flow
8. Speech-to-Code: Performance
9. Inline Suggestions: Overview
10. Inline Suggestions: Architecture
11. Inline Suggestions: Rendering
12. Inline Suggestions: Caching
13. Vector Search: Overview
14. Vector Search: Architecture
15. Vector Search: Indexing flow
16. Vector Search: Search pipeline
17. Technical achievements
18. Q&A

## Technical Details

- **File:** Single HTML file (no external dependencies except Mermaid.js CDN)
- **Size:** ~30-40 KB
- **Dependencies:** Mermaid.js v10 (CDN)
- **Browser Support:** Chrome 90+, Firefox 88+, Safari 14+

## Presenting

1. Full screen (F11 or browser fullscreen)
2. Use presenter mode if available
3. Advance slides with Space bar for smooth flow
4. Diagrams are interactive (Mermaid.js)

## Customization

All content is in the single `hackathon-presentation.html` file:
- Modify `<style>` for visual changes
- Edit slide `<div>` elements for content
- Update Mermaid diagrams by editing diagram code blocks
