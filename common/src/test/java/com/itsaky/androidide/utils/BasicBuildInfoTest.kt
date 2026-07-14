package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BasicBuildInfoTest {

  @Test
  fun `formatVersion shows release number alongside build string when release version present`() {
    assertThat(BasicBuildInfo.formatVersion("25.47", "C-r-1118-0930"))
      .isEqualTo("25.47 (C-r-1118-0930)")
  }

  @Test
  fun `formatVersion shows only the build string when release version is blank`() {
    assertThat(BasicBuildInfo.formatVersion("", "C-d-1118-0930"))
      .isEqualTo("C-d-1118-0930")
  }

  @Test
  fun `formatVersion treats a whitespace-only release version as absent`() {
    assertThat(BasicBuildInfo.formatVersion("   ", "C-d-1118-0930"))
      .isEqualTo("C-d-1118-0930")
  }
}
