package org.appdevforall.codeonthego.screens

import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.text.KButton
import org.appdevforall.codeonthego.R

object InstallToolsScreen : KScreen<InstallToolsScreen>() {
	override val layoutId: Int? = null
	override val viewClass: Class<*>? = null

	val doneButton = KButton { withId(R.id.done) }
}
