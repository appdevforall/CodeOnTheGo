package com.itsaky.androidide.quickbuild

import com.itsaky.androidide.eventbus.events.Event
import java.io.File

/**
 * Posted by [QuickBuildJumpActivity] when the Quick Build test app's error overlay is
 * tapped (plan A1); the editor activity subscribes and opens [file] at the error.
 *
 * @property file failing source file, already validated to live inside the open project.
 * @property line 1-based, as reported by the compiler; <= 0 when unknown.
 * @property column 1-based; <= 0 when unknown.
 */
class QuickBuildErrorJumpEvent(
	val file: File,
	val line: Int,
	val column: Int,
) : Event()
