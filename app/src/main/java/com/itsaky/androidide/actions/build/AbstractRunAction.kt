package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * @author Akash Yadav
 */
abstract class AbstractRunAction(
	context: Context,
	@StringRes labelRes: Int,
	@DrawableRes iconRes: Int,
) : AbstractModuleAssemblerAction(context, labelRes, iconRes)
