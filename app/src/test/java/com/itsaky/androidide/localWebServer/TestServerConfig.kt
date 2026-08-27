package com.itsaky.androidide.localWebServer

/**
 * The `ServerConfig` every test in this package uses.
 *
 * Every path is given explicitly: `ServerConfig`'s own defaults reach for external storage, which a
 * JVM test has no stub for. Shared rather than copied per class, so a new required field is a
 * one-line fix instead of a hunt, and two fixtures cannot drift into subtly different servers.
 */
internal fun testServerConfig(port: Int = 0) =
	ServerConfig(
		port = port,
		databasePath = "/nonexistent/test.db",
		fileDirPath = "/tmp",
		debugDatabasePath = "/nonexistent/debug.db",
		debugEnablePath = "/nonexistent/debug-flag",
		experimentsEnablePath = "/nonexistent/exp-flag",
		clearCacheEnablePath = "/nonexistent/cs0-flag",
		projectDatabasePath = "/nonexistent/recent-projects.db",
	)
