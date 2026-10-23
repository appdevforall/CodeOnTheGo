package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeFeatureFlagService
import com.itsaky.androidide.utils.Experiments

class IdeFeatureFlagServiceImpl : IdeFeatureFlagService {
	override fun isExperimentsEnabled(): Boolean = Experiments.enabled
}
