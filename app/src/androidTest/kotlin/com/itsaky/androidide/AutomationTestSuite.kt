package com.itsaky.androidide

import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    CleanupTest::class,
    AutomationEndToEndTest::class,
    ExportCacheDirectoryTest::class,
)
class AutomationTestSuite
