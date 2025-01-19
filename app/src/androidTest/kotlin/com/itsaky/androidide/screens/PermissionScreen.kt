package com.itsaky.androidide.screens

import android.view.View
import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.recycler.KRecyclerItem
import io.github.kakaocup.kakao.recycler.KRecyclerView
import io.github.kakaocup.kakao.text.KButton
import io.github.kakaocup.kakao.text.KTextView
import org.hamcrest.Matcher

object PermissionScreen : KScreen<PermissionScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val title = KTextView { withText(R.string.onboarding_title_permissions) }
    val subTitle = KTextView { withText(R.string.onboarding_subtitle_permissions) }

    val rvPermissions = KRecyclerView(
        builder = { withId(R.id.onboarding_items) },
        itemTypeBuilder = { itemType(::PermissionItem) }
    )

    class PermissionItem(matcher: Matcher<View>) : KRecyclerItem<PermissionItem>(matcher) {

        val grantButton = KButton(matcher) { withId(R.id.grant_button) }
        val title = KTextView(matcher) { withId(R.id.title) }
        val description = KTextView(matcher) { withId(R.id.description) }
    }
}