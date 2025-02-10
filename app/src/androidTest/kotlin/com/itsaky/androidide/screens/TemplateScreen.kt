package com.itsaky.androidide.screens

import android.view.View
import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.recycler.KRecyclerItem
import io.github.kakaocup.kakao.recycler.KRecyclerView
import io.github.kakaocup.kakao.text.KTextView
import org.hamcrest.Matcher

object TemplateScreen : KScreen<TemplateScreen>() {

    override val layoutId: Int? = null

    override val viewClass: Class<*>? = null

    val rvTemplates = KRecyclerView(
        builder = { withId(R.id.list) },
        itemTypeBuilder = { itemType(::TemplateItem) }
    )

    class TemplateItem(matcher: Matcher<View>) : KRecyclerItem<TemplateItem>(matcher) {

        val nameTemplate = KTextView(matcher) { withId(R.id.template_name) }
    }
}