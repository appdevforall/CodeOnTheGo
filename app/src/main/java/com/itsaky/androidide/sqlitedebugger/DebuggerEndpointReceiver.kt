package com.itsaky.androidide.sqlitedebugger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.itsaky.androidide.di.APPLICATION_SCOPE
import com.itsaky.androidide.repositories.DebuggerEndpointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory

/**
 * Receives the token and port an app's SQLite debugger reports once it listens (ADFA-7137).
 * Exported: the sender is another app. A broadcast from an application ID with no pairing row,
 * which means an app Code on the Go did not install or launch, stores nothing.
 */
class DebuggerEndpointReceiver :
	BroadcastReceiver(),
	KoinComponent {
	private val repository: DebuggerEndpointRepository by inject()
	private val scope: CoroutineScope by inject(named(APPLICATION_SCOPE))

	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		if (intent.action != DebuggerEndpointValidation.ACTION) return
		val endpoint: DebuggerEndpointValidation.Endpoint
		val applicationId: String
		try {
			// Unparcelling another app's extras can throw on a malformed Bundle.
			endpoint =
				DebuggerEndpointValidation.validate(
					token = intent.getStringExtra(DebuggerEndpointValidation.EXTRA_TOKEN),
					port =
						intent
							.takeIf { it.hasExtra(DebuggerEndpointValidation.EXTRA_PORT) }
							?.getIntExtra(DebuggerEndpointValidation.EXTRA_PORT, 0),
				) ?: run {
					// Never logs the token.
					log.debug("Ignored a debugger endpoint broadcast with a missing or invalid token or port")
					return
				}
			applicationId =
				DebuggerEndpointValidation.resolveSender(
					IntentCompat
						.getParcelableExtra(intent, DebuggerEndpointValidation.EXTRA_SENDER, PendingIntent::class.java)
						?.creatorPackage,
				) ?: run {
					log.warn("Ignored a debugger endpoint broadcast with no sender identity")
					return
				}
		} catch (e: Exception) {
			log.warn("Ignored a malformed debugger endpoint broadcast", e)
			return
		}

		val pending = goAsync()
		scope.launch {
			try {
				if (!repository.storeEndpoint(applicationId, endpoint)) {
					log.warn("Ignored a debugger endpoint from unpaired app {}", applicationId)
				}
			} catch (e: Exception) {
				log.warn("Could not store the debugger endpoint of {}", applicationId, e)
			} finally {
				pending.finish()
			}
		}
	}

	private companion object {
		private val log = LoggerFactory.getLogger(DebuggerEndpointReceiver::class.java)
	}
}
