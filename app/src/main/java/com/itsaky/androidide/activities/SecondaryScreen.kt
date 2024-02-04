package com.itsaky.androidide.activities

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import com.itsaky.androidide.R

class SecondaryScreen(outerContext: Context?, display: Display?) : Presentation(
    outerContext,
    display
) {
    val outerContext = outerContext
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secondary_screen)
    }
}