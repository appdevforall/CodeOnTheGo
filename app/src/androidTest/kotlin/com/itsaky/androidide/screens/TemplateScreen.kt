package com.itsaky.androidide.screens

import android.view.View
import com.itsaky.androidide.R
import com.itsaky.androidide.screens.HomeScreen.ActionItem
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.recycler.KRecyclerItem
import io.github.kakaocup.kakao.recycler.KRecyclerView
import io.github.kakaocup.kakao.text.KTextView
import org.hamcrest.Matcher
import org.hamcrest.Matchers

object TemplateScreen : KScreen<TemplateScreen>() {

    override val layoutId: Int? = null

    override val viewClass: Class<*>? = null

    val rvTemplates = KRecyclerView(
        builder = { withId(R.id.list) },
        itemTypeBuilder = { itemType(::TemplateItem) }
    )

    class TemplateItem(matcher: Matcher<View>) : KRecyclerItem<TemplateItem>(matcher) {
        // Using this property in withDescendant matcher to find the template by name
        val nameTemplate = KTextView(matcher) { withId(R.id.template_name) }
    }

    fun TestContext<Unit>.selectTemplate(templateResId: Int) {
        val templateText = device.targetContext.getString(templateResId)
        println("Selecting template with id: $templateResId $templateText")

        flakySafely(10000) {
            TemplateScreen {
                rvTemplates {
                    scrollTo(0)
                    childWith<TemplateItem> {
                        withDescendant { withText(Matchers.equalToIgnoringCase(templateText)) }
                    } perform {
                        isDisplayed()
                        click()
                    }
                }
            }
        }
    }
}