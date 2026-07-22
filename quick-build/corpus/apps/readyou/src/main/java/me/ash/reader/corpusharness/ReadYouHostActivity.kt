package me.ash.reader.corpusharness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.dollarLast
import me.ash.reader.ui.ext.formatAsString
import me.ash.reader.ui.ext.formatUrl
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.isUrl
import me.ash.reader.ui.ext.md5
import me.ash.reader.ui.ext.mask
import me.ash.reader.ui.ext.spacerDollar
import me.ash.reader.ui.ext.toBoolean
import java.util.Date

/**
 * Corpus harness entry point: exercises a real ReadYou util/domain subgraph
 * (DESUtils, DateExt, StringExt, NumberExt) with no Room/Hilt/Compose/WorkManager
 * framework wiring. See README.md.
 */
class ReadYouHostActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val secret = "feed-token-123"
		val roundTripped = DESUtils.decrypt(DESUtils.encrypt(secret)) == secret

		val url = "example.com/feed".formatUrl()
		val groupId = 3.getDefaultGroupId()
		val groupNumber = groupId.dollarLast()

		val summary = buildString {
			append("roundTripped=").append(roundTripped)
			append(" url=").append(url).append(" isUrl=").append(url.isUrl())
			append(" group=").append(groupNumber)
			append(" md5=").append("hello".md5())
			append(" masked=").append("secret".mask())
			append(" html=").append("<b>bold</b>".decodeHTML())
			append(" flag=").append(1.toBoolean())
			append(" ").append(Date().formatAsString(this@ReadYouHostActivity))
		}

		val view = TextView(this)
		view.id = ID_SUMMARY
		view.text = summary
		setContentView(view)
	}

	companion object {
		const val ID_SUMMARY = 3001
	}
}
