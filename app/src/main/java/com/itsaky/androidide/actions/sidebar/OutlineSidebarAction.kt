package com.itsaky.androidide.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import com.itsaky.androidide.fragments.sidebar.OutlineFragment
import com.itsaky.androidide.idetooltips.TooltipTag
import kotlin.reflect.KClass

class OutlineSidebarAction(
	context: Context,
	override val order: Int,
) : AbstractSidebarAction() {
	companion object {
		const val ID = "ide.editor.sidebar.outline"
	}

	override val id: String = ID
	override val fragmentClass: KClass<out Fragment> = OutlineFragment::class

	init {
		label = context.getString(R.string.title_document_outline)
		icon = ContextCompat.getDrawable(context, R.drawable.ic_outline)
	}

	override fun retrieveTooltipTag(isAlternateContext: Boolean) = TooltipTag.OUTLINE_SIDEBAR
}
