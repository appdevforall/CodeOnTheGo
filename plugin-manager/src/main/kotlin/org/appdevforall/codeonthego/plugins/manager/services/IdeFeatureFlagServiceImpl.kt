package org.appdevforall.codeonthego.plugins.manager.services

import org.appdevforall.codeonthego.plugins.services.IdeFeatureFlagService
import org.appdevforall.codeonthego.utils.FeatureFlags

class IdeFeatureFlagServiceImpl : IdeFeatureFlagService {

    override fun isExperimentsEnabled(): Boolean = FeatureFlags.isExperimentsEnabled
}
