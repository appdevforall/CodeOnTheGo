package com.itsaky.androidide.agent.repository

/**
 * Base exception for scenarios where the planner's response is rejected due to tool invocation issues.
 */
open class PlannerToolCallException(
    message: String,
    val toolName: String? = null,
    val rawCandidate: String? = null
) : Exception(message)

/**
 * Thrown when the model emits a syntactically invalid tool call payload.
 */
class MalformedToolCallException(
    message: String,
    toolName: String? = null,
    rawCandidate: String? = null
) : PlannerToolCallException(message, toolName, rawCandidate)

/**
 * Thrown when the model tries to call a tool even though the platform rejected tool use altogether.
 */
class UnexpectedToolCallException(
    message: String,
    toolName: String? = null,
    rawCandidate: String? = null
) : PlannerToolCallException(message, toolName, rawCandidate)
