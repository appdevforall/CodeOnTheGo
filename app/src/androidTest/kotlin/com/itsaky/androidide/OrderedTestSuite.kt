package com.itsaky.androidide

import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    CleanupTest::class,
    EndToEndTest::class,
)
class OrderedTestSuite
