package org.secuso.privacyfriendlytodolist.util

import android.content.Context
import org.secuso.privacyfriendlytodolist.model.TodoTask

// Scaffolding, not vendored: the real NotificationMgr builds an androidx.core.app.NotificationCompat
// with channel setup, TaskStackBuilder deep-links into MainActivity, and multiple actions (snooze,
// snooze-until-deadline, done) -- none of that UI/notification-plumbing is part of the acyclic
// alarm/boot subgraph this app was picked for. This stand-in keeps only the call shape the real,
// vendored service/HandleAlarmJob.kt and service/NotificationJob.kt use.
object NotificationMgr {
    const val EXTRA_NOTIFICATION_ACTION_ID = "EXTRA_NOTIFICATION_ACTION_ID"
    const val EXTRA_NOTIFICATION_TASK_ID = "EXTRA_NOTIFICATION_TASK_ID"

    fun postTaskNotification(context: Context, title: String, message: String?, task: TodoTask): Int {
        return task.getId()
    }

    fun cancelNotification(context: Context, notificationId: Int) {
    }
}
