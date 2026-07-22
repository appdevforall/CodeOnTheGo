package org.secuso.privacyfriendlytodolist.model

import org.secuso.privacyfriendlytodolist.util.Timestamp

// Scaffolding, not vendored: the real ModelServices is Room-backed (coroutine-dispatched DB
// access with a DeliveryOption/dispatchResult mechanism). This stand-in keeps only the call
// shapes that the real, vendored service/*Job.kt files use, dropping the coroutine plumbing
// entirely -- none of it is part of the alarm/boot-receiver subgraph this app was picked for.
typealias ResultConsumer<T> = (T) -> Unit

interface ModelServices {
    fun getTaskById(todoTaskId: Int, resultConsumer: ResultConsumer<TodoTask?>)
    fun getNextTaskToRemind(now: Timestamp, resultConsumer: ResultConsumer<Pair<TodoTask?, Int>>)
    fun getTasksWithOverdueReminders(now: Timestamp, resultConsumer: ResultConsumer<Pair<MutableList<TodoTask>, Int>>)
    fun saveTodoTaskInDb(todoTask: TodoTask, resultConsumer: ResultConsumer<Int>)
    fun notifyDataChangedOutsideAppUI(changedLists: Int, changedTasks: Int, changedSubtasks: Int)
}

class StubModelServices : ModelServices {
    override fun getTaskById(todoTaskId: Int, resultConsumer: ResultConsumer<TodoTask?>) {}
    override fun getNextTaskToRemind(now: Timestamp, resultConsumer: ResultConsumer<Pair<TodoTask?, Int>>) {}
    override fun getTasksWithOverdueReminders(now: Timestamp, resultConsumer: ResultConsumer<Pair<MutableList<TodoTask>, Int>>) {}
    override fun saveTodoTaskInDb(todoTask: TodoTask, resultConsumer: ResultConsumer<Int>) {}
    override fun notifyDataChangedOutsideAppUI(changedLists: Int, changedTasks: Int, changedSubtasks: Int) {}
}
