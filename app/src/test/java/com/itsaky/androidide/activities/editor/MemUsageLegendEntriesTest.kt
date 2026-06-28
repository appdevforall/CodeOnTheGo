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

package com.itsaky.androidide.activities.editor

import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [buildMemUsageLegendEntries] (ADFA-4327 / Sentry APPDEVFORALL-31).
 *
 * The mem-usage chart crashed in MPAndroidChart's `LegendRenderer.renderLegend` with an
 * `IndexOutOfBoundsException` when the auto-computed legend desynced from the dataset count as that
 * count changed at runtime (Gradle daemons connecting → `resetMemUsageChart` rebuilds the datasets).
 * The fix sets the legend entries EXPLICITLY so they always match the datasets. These tests pin that
 * invariant — one entry per dataset, label/color carried through, and the entry count tracking the
 * dataset count across a count change (the exact condition that triggered the renderer desync).
 *
 * The literal draw-time IndexOutOfBoundsException is a race inside the third-party renderer and is
 * verified on-device (A56 recording on the PR); this test guards the anti-desync invariant the fix
 * relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemUsageLegendEntriesTest {

  private fun dataSet(label: String, color: Int): LineDataSet =
    LineDataSet(listOf(Entry(0f, 0f), Entry(1f, 1f)), label).also { it.color = color }

  @Test
  fun `produces one entry per dataset with matching label and color`() {
    val datasets: List<ILineDataSet> =
      listOf(
        dataSet("Code on the Go - 12.00MB", 0xFF0000FF.toInt()),
        dataSet("gradle daemon - 34.00MB", 0xFF00FF00.toInt()),
      )

    val entries = buildMemUsageLegendEntries(datasets)

    assertThat(entries).hasSize(2)
    assertThat(entries.map { it.label })
      .containsExactly("Code on the Go - 12.00MB", "gradle daemon - 34.00MB")
      .inOrder()
    assertThat(entries.map { it.formColor })
      .containsExactly(0xFF0000FF.toInt(), 0xFF00FF00.toInt())
      .inOrder()
    assertThat(entries.map { it.form })
      .containsExactly(Legend.LegendForm.DEFAULT, Legend.LegendForm.DEFAULT)
  }

  @Test
  fun `entry count tracks dataset count across a count change`() {
    // 1 dataset (IDE only) -> 3 datasets (Gradle tooling + daemon connect). The legend entry list
    // must always equal the current dataset count; that consistency is exactly what stops the
    // LegendRenderer from indexing a stale entry array (the ADFA-4327 crash).
    val before = buildMemUsageLegendEntries(listOf(dataSet("ide", 1)))
    assertThat(before).hasSize(1)

    val after =
      buildMemUsageLegendEntries(
        listOf(dataSet("ide", 1), dataSet("tooling", 2), dataSet("daemon", 3)),
      )
    assertThat(after).hasSize(3)
    assertThat(after.map { it.label }).containsExactly("ide", "tooling", "daemon").inOrder()
  }

  @Test
  fun `empty datasets produce empty entries`() {
    assertThat(buildMemUsageLegendEntries(emptyList())).isEmpty()
  }
}
