package com.itsaky.androidide.quickbuild.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a user component class to the proxy class the manifest declares for it.
 *
 * Baked into the proxy app APK as {@code assets/quickbuild/components.json}, a JSON object of {@code "com.example.MainActivity": "<proxy activity class>"}. The manifest needs stable component names while the user's classes stay swappable inside the payload dex, so the runtime translates through this map before launching anything.
 */
final class ComponentMap {

	static final ComponentMap EMPTY = new ComponentMap(Collections.<String, String> emptyMap());

	/**
	 * Parses the component map, dropping non-string values so a schema extension never bricks an installed proxy app.
	 *
	 * @throws IllegalArgumentException
	 *             on malformed JSON.
	 */
	static ComponentMap parse(String json) {
		Map<String, Object> obj = MiniJson.parseObject(json);
		Map<String, String> mapped = new LinkedHashMap<String, String>();
		for (Map.Entry<String, Object> entry : obj.entrySet()) {
			if (entry.getValue() instanceof String) {
				mapped.put(entry.getKey(), (String) entry.getValue());
			}
		}
		return new ComponentMap(Collections.unmodifiableMap(mapped));
	}

	private final Map<String, String> userToProxy;

	private ComponentMap(Map<String, String> userToProxy) {
		this.userToProxy = userToProxy;
	}

	/** The manifest-declared proxy class for {@code userClassName}, or null when unmapped. */
	String proxyFor(String userClassName) {
		return userClassName == null ? null : userToProxy.get(userClassName);
	}

	int size() {
		return userToProxy.size();
	}
}
