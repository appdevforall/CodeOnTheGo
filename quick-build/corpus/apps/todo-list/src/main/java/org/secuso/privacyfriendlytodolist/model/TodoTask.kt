package org.secuso.privacyfriendlytodolist.model

import org.secuso.privacyfriendlytodolist.util.Timestamp

// Scaffolding, not vendored: the real TodoTask is a much larger interface (subtasks, lists,
// pomodoro, priority, Parcelable) that this app's acyclic alarm/boot subgraph never needs.
// This stand-in keeps only the members the real, vendored util/AlarmMgr.kt, util/Timestamp.kt,
// and service/*Job.kt files actually call. The two enums are copied verbatim from the real
// TodoTask.kt (not re-authored) because Timestamp.kt's real addInterval()/getNextRecurringDate()
// switches on RecurrencePattern by ordinal/range -- an approximation would silently change that
// real logic's behavior.
interface TodoTask {

    enum class RecurrencePattern {
        NONE,
        DAILY,
        WEEKLY,
        MONTHLY,
        YEARLY,
                          WEEKDAYS_M______, WEEKDAYS__T_____, WEEKDAYS_MT_____, WEEKDAYS___W____, WEEKDAYS_M_W____, WEEKDAYS__TW____, WEEKDAYS_MTW____,
        WEEKDAYS____T___, WEEKDAYS_M__T___, WEEKDAYS__T_T___, WEEKDAYS_MT_T___, WEEKDAYS___WT___, WEEKDAYS_M_WT___, WEEKDAYS__TWT___, WEEKDAYS_MTWT___,
        WEEKDAYS_____F__, WEEKDAYS_M___F__, WEEKDAYS__T__F__, WEEKDAYS_MT__F__, WEEKDAYS___W_F__, WEEKDAYS_M_W_F__, WEEKDAYS__TW_F__, WEEKDAYS_MTW_F__,
        WEEKDAYS____TF__, WEEKDAYS_M__TF__, WEEKDAYS__T_TF__, WEEKDAYS_MT_TF__, WEEKDAYS___WTF__, WEEKDAYS_M_WTF__, WEEKDAYS__TWTF__, WEEKDAYS_MTWTF__,
        WEEKDAYS______S_, WEEKDAYS_M____S_, WEEKDAYS__T___S_, WEEKDAYS_MT___S_, WEEKDAYS___W__S_, WEEKDAYS_M_W__S_, WEEKDAYS__TW__S_, WEEKDAYS_MTW__S_,
        WEEKDAYS____T_S_, WEEKDAYS_M__T_S_, WEEKDAYS__T_T_S_, WEEKDAYS_MT_T_S_, WEEKDAYS___WT_S_, WEEKDAYS_M_WT_S_, WEEKDAYS__TWT_S_, WEEKDAYS_MTWT_S_,
        WEEKDAYS_____FS_, WEEKDAYS_M___FS_, WEEKDAYS__T__FS_, WEEKDAYS_MT__FS_, WEEKDAYS___W_FS_, WEEKDAYS_M_W_FS_, WEEKDAYS__TW_FS_, WEEKDAYS_MTW_FS_,
        WEEKDAYS____TFS_, WEEKDAYS_M__TFS_, WEEKDAYS__T_TFS_, WEEKDAYS_MT_TFS_, WEEKDAYS___WTFS_, WEEKDAYS_M_WTFS_, WEEKDAYS__TWTFS_, WEEKDAYS_MTWTFS_,
        WEEKDAYS_______S, WEEKDAYS_M_____S, WEEKDAYS__T____S, WEEKDAYS_MT____S, WEEKDAYS___W___S, WEEKDAYS_M_W___S, WEEKDAYS__TW___S, WEEKDAYS_MTW___S,
        WEEKDAYS____T__S, WEEKDAYS_M__T__S, WEEKDAYS__T_T__S, WEEKDAYS_MT_T__S, WEEKDAYS___WT__S, WEEKDAYS_M_WT__S, WEEKDAYS__TWT__S, WEEKDAYS_MTWT__S,
        WEEKDAYS_____F_S, WEEKDAYS_M___F_S, WEEKDAYS__T__F_S, WEEKDAYS_MT__F_S, WEEKDAYS___W_F_S, WEEKDAYS_M_W_F_S, WEEKDAYS__TW_F_S, WEEKDAYS_MTW_F_S,
        WEEKDAYS____TF_S, WEEKDAYS_M__TF_S, WEEKDAYS__T_TF_S, WEEKDAYS_MT_TF_S, WEEKDAYS___WTF_S, WEEKDAYS_M_WTF_S, WEEKDAYS__TWTF_S, WEEKDAYS_MTWTF_S,
        WEEKDAYS______SS, WEEKDAYS_M____SS, WEEKDAYS__T___SS, WEEKDAYS_MT___SS, WEEKDAYS___W__SS, WEEKDAYS_M_W__SS, WEEKDAYS__TW__SS, WEEKDAYS_MTW__SS,
        WEEKDAYS____T_SS, WEEKDAYS_M__T_SS, WEEKDAYS__T_T_SS, WEEKDAYS_MT_T_SS, WEEKDAYS___WT_SS, WEEKDAYS_M_WT_SS, WEEKDAYS__TWT_SS, WEEKDAYS_MTWT_SS,
        WEEKDAYS_____FSS, WEEKDAYS_M___FSS, WEEKDAYS__T__FSS, WEEKDAYS_MT__FSS, WEEKDAYS___W_FSS, WEEKDAYS_M_W_FSS, WEEKDAYS__TW_FSS, WEEKDAYS_MTW_FSS,
        WEEKDAYS____TFSS, WEEKDAYS_M__TFSS, WEEKDAYS__T_TFSS, WEEKDAYS_MT_TFSS, WEEKDAYS___WTFSS, WEEKDAYS_M_WTFSS, WEEKDAYS__TWTFSS, WEEKDAYS_MTWTFSS,
    }

    enum class ReminderState {
        INITIAL,
        DONE,
    }

    fun getId(): Int
    fun getName(): String
    fun getDeadline(): Timestamp?
    fun isRecurring(): Boolean
    fun getRecurrencePattern(): RecurrencePattern
    fun getRecurrenceInterval(): Int
    fun getReminderTime(): Timestamp?
    fun computeReminderTimeAtDeadline(now: Timestamp): Timestamp?
    fun setReminderState(reminderState: ReminderState)
    fun setChanged()
    fun setDone(isDone: Boolean)
    fun isDone(): Boolean
}
