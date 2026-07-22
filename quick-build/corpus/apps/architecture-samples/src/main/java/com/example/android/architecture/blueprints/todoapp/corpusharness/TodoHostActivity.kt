package com.example.android.architecture.blueprints.todoapp.corpusharness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.example.android.architecture.blueprints.todoapp.data.Task
import com.example.android.architecture.blueprints.todoapp.data.toExternal
import com.example.android.architecture.blueprints.todoapp.data.toLocal
import com.example.android.architecture.blueprints.todoapp.data.toNetwork
import com.example.android.architecture.blueprints.todoapp.statistics.getActiveAndCompletedStats
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType
import com.example.android.architecture.blueprints.todoapp.util.Async

/**
 * Corpus harness entry point: exercises the real architecture-samples data-layer
 * subgraph (Task / TaskRepository / ModelMappingExt / StatisticsUtils / Async /
 * TasksFilterType) with no DI, Room, or Compose framework wiring. See README.md.
 */
class TodoHostActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val tasks = listOf(
			Task(title = "Buy milk", description = "2%", isCompleted = false, id = "1"),
			Task(title = "Walk dog", description = "Morning walk", isCompleted = true, id = "2"),
		)
		val stats = getActiveAndCompletedStats(tasks)
		val roundTripped = tasks.toLocal().toNetwork().toLocal().toExternal()
		val filter = TasksFilterType.ALL_TASKS
		val asyncResult: Async<List<Task>> = Async.Success(roundTripped)

		val successCount = when (asyncResult) {
			is Async.Success -> asyncResult.data.size
			else -> 0
		}

		val view = TextView(this)
		view.id = ID_STATUS
		view.text = "$filter: ${stats.activeTasksPercent}% active, " +
			"${stats.completedTasksPercent}% completed ($successCount round-tripped)"
		setContentView(view)
	}

	companion object {
		const val ID_STATUS = 2001
	}
}
