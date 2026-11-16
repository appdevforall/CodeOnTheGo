package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import java.util.Objects;

/**
 * Where one app's SQLite debugger server is listening, as the app last reported it to the IDE. Immutable.
 *
 * <p>
 * The port and the token are chosen by the app on every start, so a value read here goes stale when the app dies or is relaunched; a client that is refused (or answered 401/403) asks {@link DebuggerEndpointService#getEndpoints} again instead of retrying the same values. The token is a credential for the debugger server: do not log it or persist it. {@link #toString} leaves it out.
 */
public final class DebuggerEndpoint {

	private final String applicationId;
	private final int port;
	private final String token;
	private final long updatedAtMillis;

	/**
	 * Creates an endpoint.
	 *
	 * @param applicationId
	 *            the app's package name (must not be null)
	 * @param port
	 *            the TCP port the debugger server listens on, on the loopback interface
	 * @param token
	 *            the bearer token the server requires (must not be null)
	 * @param updatedAtMillis
	 *            when the app reported these values, in milliseconds since the Unix epoch
	 */
	public DebuggerEndpoint(@NonNull String applicationId, int port, @NonNull String token, long updatedAtMillis) {
		this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
		this.port = port;
		this.token = Objects.requireNonNull(token, "token");
		this.updatedAtMillis = updatedAtMillis;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof DebuggerEndpoint)) {
			return false;
		}
		final DebuggerEndpoint that = (DebuggerEndpoint) other;
		return port == that.port && updatedAtMillis == that.updatedAtMillis && applicationId.equals(that.applicationId) && token.equals(that.token);
	}

	/**
	 * Gets the app's package name, which identifies the app within the project.
	 *
	 * @return the application ID (never null)
	 */
	@NonNull
	public String getApplicationId() {
		return applicationId;
	}

	/**
	 * Gets the port the debugger server listens on, on {@code 127.0.0.1}.
	 *
	 * @return the port
	 */
	public int getPort() {
		return port;
	}

	/**
	 * Gets the bearer token the server requires on every request.
	 *
	 * @return the token (never null)
	 */
	@NonNull
	public String getToken() {
		return token;
	}

	/**
	 * Gets when the app reported {@link #getPort} and {@link #getToken}.
	 *
	 * @return milliseconds since the Unix epoch
	 */
	public long getUpdatedAtMillis() {
		return updatedAtMillis;
	}

	@Override
	public int hashCode() {
		return Objects.hash(applicationId, port, token, updatedAtMillis);
	}

	@NonNull
	@Override
	public String toString() {
		return "DebuggerEndpoint{applicationId=" + applicationId + ", port=" + port + ", updatedAtMillis=" + updatedAtMillis + "}";
	}
}
