package org.appdevforall.codeonthego

import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
	CleanupTest::class,
	EndToEndTest::class,
	QuickBuildPipelineTest::class,
	QuickBuildSmokeTest::class,
	QuickBuildFlagOffTest::class,
)
class OrderedTestSuite
