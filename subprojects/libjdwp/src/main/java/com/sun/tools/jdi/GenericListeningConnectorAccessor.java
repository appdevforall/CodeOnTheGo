package com.sun.tools.jdi;

import com.sun.jdi.connect.Connector;

import java.util.Map;

/**
 * @author Akash Yadav
 */
public class GenericListeningConnectorAccessor {

	public static boolean isListening(GenericListeningConnector connector, Map<String, ? extends Connector.Argument> args) {
		if (connector == null || connector.listenMap == null) {
			return false;
		}

		return connector.listenMap.containsKey(args);
	}
}
