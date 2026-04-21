package com.itsaky.androidide.screens

import android.view.View
import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.recycler.KRecyclerItem
import io.github.kakaocup.kakao.recycler.KRecyclerView
import io.github.kakaocup.kakao.text.KTextView
import org.hamcrest.Matcher

object PermissionsInfoScreen : KScreen<PermissionsInfoScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val introText = KTextView { withId(R.id.intro_text) }

    val permissionsList = KRecyclerView(
        builder = { withId(R.id.permissions_list) },
        itemTypeBuilder = { itemType(::InfoItem) },
    )

    class InfoItem(matcher: Matcher<View>) : KRecyclerItem<InfoItem>(matcher) {
        val text = KTextView(matcher) { withId(R.id.text) }
    }
}
