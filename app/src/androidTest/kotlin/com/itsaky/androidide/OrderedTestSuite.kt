package com.itsaky.androidide

import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    WelcomeScreenTest::class,
    PermissionsScreenTest::class,
    ProjectBuildTestWithKtsGradle::class,
    CleanupTest::class
)
class OrderedTestSuite