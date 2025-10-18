package com.itsaky.androidide.agent.repository

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    FAILED
}

data class TaskStep(
    val description: String,
    val status: StepStatus = StepStatus.PENDING,
    val result: String? = null
) {
    fun withStatus(newStatus: StepStatus, newResult: String? = result): TaskStep =
        copy(status = newStatus, result = newResult)
}

data class Plan(val steps: MutableList<TaskStep>) {

    fun deepCopy(): Plan = Plan(steps.map { it.copy() }.toMutableList())

    fun withUpdatedStep(index: Int, updater: (TaskStep) -> TaskStep): Plan {
        if (index !in steps.indices) return this
        val updatedSteps = steps.mapIndexed { idx, step ->
            if (idx == index) updater(step) else step.copy()
        }.toMutableList()
        return copy(steps = updatedSteps)
    }

    fun firstActionableIndex(): Int? {
        val idx = steps.indexOfFirst {
            it.status == StepStatus.PENDING || it.status == StepStatus.IN_PROGRESS
        }
        return if (idx >= 0) idx else null
    }

    fun isComplete(): Boolean = steps.isNotEmpty() && steps.all { it.status == StepStatus.DONE }
}
