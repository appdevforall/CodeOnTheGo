@file:Suppress(
	"EXPOSED_TYPE_PARAMETER_BOUND_DEPRECATION_WARNING",
	"FunctionName",
	"ObjectPropertyName",
)

package com.sun.tools.jdi

import com.sun.jdi.connect.Connector
import com.sun.jdi.connect.IllegalConnectorArgumentsException
import com.sun.jdi.connect.Transport

var <T : GenericListeningConnector> T._transport: Transport
	get() = this.transport
	set(value) {
		this.transport = value
	}

fun Connector._addStringArgument(
	name: String?,
	label: String?,
	description: String?,
	defaultValue: String?,
	mustSpecify: Boolean,
) = (this as ConnectorImpl).addStringArgument(name, label, description, defaultValue, mustSpecify)

fun Connector._addBooleanArgument(
	name: String?,
	label: String?,
	description: String?,
	defaultValue: Boolean,
	mustSpecify: Boolean,
) = (this as ConnectorImpl).addBooleanArgument(name, label, description, defaultValue, mustSpecify)

fun Connector._addIntegerArgument(
	name: String?,
	label: String?,
	description: String?,
	defaultValue: String?,
	mustSpecify: Boolean,
	min: Int,
	max: Int,
) = (this as ConnectorImpl).addIntegerArgument(name, label, description, defaultValue, mustSpecify, min, max)

fun Connector._addSelectedArgument(
	name: String?,
	label: String?,
	description: String?,
	defaultValue: String?,
	mustSpecify: Boolean,
	list: List<String?>?,
) = (this as ConnectorImpl).addSelectedArgument(name, label, description, defaultValue, mustSpecify, list)

fun Connector._getString(name: String): String = (this as ConnectorImpl).getString(name)

@Suppress("UNCHECKED_CAST")
@Throws(IllegalConnectorArgumentsException::class)
fun Connector._argument(
	name: String,
	arguments: Map<String, Connector.Argument>,
): Connector.Argument = (this as ConnectorImpl).argument(name, arguments)
