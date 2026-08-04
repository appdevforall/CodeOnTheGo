package com.itsaky.androidide.quickbuild.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a user component class to the proxy class the manifest declares for it.
 *
 * Baked into the proxy app APK as {@code assets/quickbuild/components.json}, a JSON object of
 * {@code "com.example.MainActivity": "<proxy activity class>"}. The manifest needs stable
 * component names while the user's classes stay swappable inside the payload dex, so the runtime
 * translates through this map before launching anything.
 */
final class ComponentMap {

	/** The mapping used when the proxy app ships no components.json; every lookup returns null. */
	static final ComponentMap EMPTY = new ComponentMap(Collections.<String, String> emptyMap());

	/**
	 * Parses the component map, dropping non-string values so a schema extension never bricks an
	 * installed proxy app.
	 *
	 * @param json the whole {@code assets/quickbuild/components.json} text; must be a JSON object
	 * @return an immutable map holding only the string-valued entries, possibly empty
	 * @throws IllegalArgumentException when {@code json} is not a well-formed JSON object, which
	 *     means the proxy app's asset is corrupt
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

	/**
	 * The manifest-declared proxy class for {@code userClassName}, or null when unmapped.
	 *
	 * @param userClassName binary name of the user's own component class; null is tolerated and
	 *     answered with null, so callers need not pre-check
	 * @return the proxy class name to launch, or null when this component has no proxy
	 */
	String proxyFor(String userClassName) {
		return userClassName == null ? null : userToProxy.get(userClassName);
	}

	/**
	 * How many components the proxy app declares, for logging and tests.
	 *
	 * @return the entry count, 0 for {@link #EMPTY}
	 */
	int size() {
		return userToProxy.size();
	}
}
