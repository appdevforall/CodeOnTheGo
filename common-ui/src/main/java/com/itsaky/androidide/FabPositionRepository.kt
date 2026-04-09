package com.itsaky.androidide

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FabPositionRepository(private val context: Context) {
    private companion object {
        const val FAB_PREFS = "FabPrefs"
        const val KEY_FAB_X_RATIO = "fab_x_ratio"
        const val KEY_FAB_Y_RATIO = "fab_y_ratio"
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)

    fun savePositionRatios(xRatio: Float, yRatio: Float) {
        prefs.edit().apply {
            putFloat(KEY_FAB_X_RATIO, xRatio)
            putFloat(KEY_FAB_Y_RATIO, yRatio)
            apply()
        }
    }

    suspend fun readPositionRatios(): Pair<Float, Float> = withContext(Dispatchers.IO) {
        prefs.getFloat(KEY_FAB_X_RATIO, -1f) to prefs.getFloat(KEY_FAB_Y_RATIO, -1f)
    }
}
