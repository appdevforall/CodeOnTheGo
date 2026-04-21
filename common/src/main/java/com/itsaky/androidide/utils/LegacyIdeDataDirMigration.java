/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.utils;

import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time rename of the fork-era {@code .androidide} directory to {@link
 * SharedEnvironment#PROJECT_CACHE_DIR_NAME}.
 */
public final class LegacyIdeDataDirMigration {

	private static final Logger LOG = LoggerFactory.getLogger(LegacyIdeDataDirMigration.class);

	private LegacyIdeDataDirMigration() {}

	/**
	 * Resolves which IDE data directory path is in use: prefers {@code current} after a successful
	 * rename from {@code legacy}. If {@code current} already exists, it wins (both-existing case is
	 * logged). If only {@code legacy} exists and {@code legacy.renameTo(current)} fails, returns
	 * {@code legacy} so callers keep using the existing tree. If neither exists, returns {@code
	 * current} for the caller to create.
	 */
	public static File migrateLegacyIdeDataDirIfNeeded(File legacy, File current) {
		if (current.exists() && legacy.exists()) {
			LOG.warn(
				"Both {} and {} exist; using {} only",
				legacy.getAbsolutePath(),
				current.getAbsolutePath(),
				current.getAbsolutePath());
			return current;
		}
		if (legacy.exists()) {
			if (legacy.renameTo(current)) {
				return current;
			}
			LOG.warn("Failed to rename legacy IDE data dir {} to {}", legacy, current);
			return legacy;
		}
		return current;
	}
}
