package com.itsaky.androidide.agent.tool.shell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ParsedCommand {
    abstract val command: String

    @SerialName("is_exploration")
    abstract val isExploration: Boolean

    @Serializable
    @SerialName("read")
    data class Read(
        override val command: String,
        val files: List<String>
    ) : ParsedCommand() {
        override val isExploration: Boolean = true
    }

    @Serializable
    @SerialName("list_files")
    data class ListFiles(
        override val command: String,
        val path: String
    ) : ParsedCommand() {
        override val isExploration: Boolean = true
    }

    @Serializable
    @SerialName("search")
    data class Search(
        override val command: String,
        val query: String,
        val path: String? = null
    ) : ParsedCommand() {
        override val isExploration: Boolean = true
    }

    @Serializable
    @SerialName("unknown")
    data class Unknown(
        override val command: String
    ) : ParsedCommand() {
        override val isExploration: Boolean = false
    }
}
