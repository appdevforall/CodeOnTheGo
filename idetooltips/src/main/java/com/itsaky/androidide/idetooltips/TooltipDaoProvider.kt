package com.itsaky.androidide.idetooltips

import android.content.Context

object TooltipDaoProvider {
    private var database: IDETooltipDatabase? = null

    fun init(context: Context) {
        if (database == null) {
            database = IDETooltipDatabase.getDatabase(context)
        }
    }

    val ideTooltipDao: IDETooltipDao
        get() {
            return database!!.ideTooltipDao()
        }
}
