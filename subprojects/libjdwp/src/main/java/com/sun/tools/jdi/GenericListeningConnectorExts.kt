package com.sun.tools.jdi

import com.sun.jdi.connect.Connector

/**
 * Check whether the connector is listening.
 *
 * @param args The arguments passed to the connector.
 * @return `true` if the connector is listening, `false` otherwise.
 */
fun GenericListeningConnector?.isListening(args: Map<String, Connector.Argument>): Boolean
	 = this?.listenMap?.containsKey(args) == true