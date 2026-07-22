package dev.jdtech.jellyfin.corpusharness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesState
import java.util.UUID

/**
 * Corpus harness entry point: exercises a real findroid subgraph across its
 * setup/data/core modules (AddServerAction, ServerAddressesState, Server,
 * ServerAddress, DiscoveredServer) with no Room/Hilt/Compose/repository
 * wiring. See README.md.
 */
class FindroidHostActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val action: AddServerAction = AddServerAction.OnConnectClick("http://jellyfin.local")
		val server = Server(id = "s1", name = "Home", currentServerAddressId = null, currentUserId = null)
		val address = ServerAddress(id = UUID.randomUUID(), serverId = server.id, address = "http://jellyfin.local")
		val state = ServerAddressesState(addresses = listOf(address))
		val discovered = DiscoveredServer(id = "d1", name = "Discovered Home", address = "http://192.168.1.5")

		val summary = "action=$action addresses=${state.addresses.size} discovered=${discovered.name}"

		val view = TextView(this)
		view.id = ID_SUMMARY
		view.text = summary
		setContentView(view)
	}

	companion object {
		const val ID_SUMMARY = 7001
	}
}
