package com.itsaky.androidide.screens

import android.view.View
import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.recycler.KRecyclerItem
import io.github.kakaocup.kakao.recycler.KRecyclerView
import io.github.kakaocup.kakao.text.KButton
import org.hamcrest.Matcher

object HomeScreen : KScreen<HomeScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val rvActions = KRecyclerView(
        builder = { withId(R.id.actions) },
        itemTypeBuilder = { itemType(::ActionItem) }
    )

    class ActionItem(matcher: Matcher<View>) : KRecyclerItem<ActionItem>(matcher) {

        val button = KButton(matcher) { withId(R.id.itemButton) }
    }
}