/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

/**
 * Whether an indefinite error bar also dismisses on any tap or swipe, not only via its
 * explicit Dismiss button. Gated on [FeatureFlags.isExperimentsEnabled]: the touch-dismiss
 * change was driven by Quick Build (the bar occludes the toolbar's Run/Quick Build buttons),
 * and for flag-off users an accidental brush must not dismiss an unread error, so they keep
 * the Dismiss-button-only behavior until this ships on its own merits.
 *
 * Lives in its own file (not FlashbarActivityUtils.kt, whose top-level vals need
 * android.graphics.Color) so JVM unit tests can load it.
 */
internal fun indefiniteErrorBarDismissesOnTouch(): Boolean = FeatureFlags.isExperimentsEnabled
