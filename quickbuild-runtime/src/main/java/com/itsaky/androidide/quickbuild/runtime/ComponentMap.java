package com.itsaky.androidide.quickbuild.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The user-class-to-proxy-class map baked into the test APK as {@code assets/quickbuild/components.json} by the setup build - a JSON object of {@code "com.example.MainActivity": "<proxy activity class>"} entries.
 *
 * The map exists because the manifest needs STABLE component names (the proxies) while the user's classes stay swappable inside the payload dex: launching the entry activity by its user-class name would bypass the manifest, so the runtime translates through this map first. Plain Java so lookup and parsing are JVM-unit-testable.
 */
final class ComponentMap {

	static final ComponentMap EMPTY = new ComponentMap(Collections.<String, String> emptyMap());

	/**
	 * Parses the component map; non-string values are dropped rather than rejected so a schema extension never bricks an installed test app. Malformed JSON throws {@link IllegalArgumentException}.
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
