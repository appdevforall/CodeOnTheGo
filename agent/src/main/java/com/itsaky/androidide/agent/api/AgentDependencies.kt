package com.itsaky.androidide.agent.api

object AgentDependencies {
    @Volatile
    private var toolingApi: IdeToolingApi? = null

    fun registerToolingApi(api: IdeToolingApi) {
        toolingApi = api
    }

    fun clear() {
        toolingApi = null
    }

    fun requireToolingApi(): IdeToolingApi {
        return toolingApi
            ?: error("IdeToolingApi has not been registered. Call AgentDependencies.registerToolingApi() from the host app.")
    }
}
