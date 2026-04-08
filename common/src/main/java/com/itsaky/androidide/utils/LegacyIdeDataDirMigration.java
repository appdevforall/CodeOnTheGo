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
	 * If {@code current} does not exist but {@code legacy} does, renames {@code legacy} to {@code
	 * current}. If both exist, logs a warning and leaves {@code current} as the source of truth.
	 */
	public static void migrateLegacyIdeDataDirIfNeeded(File legacy, File current) {
		if (current.exists()) {
			if (legacy.exists()) {
				LOG.warn(
						"Both {} and {} exist; using {} only",
						legacy.getAbsolutePath(),
						current.getAbsolutePath(),
						current.getAbsolutePath());
			}
			return;
		}
		if (legacy.exists() && !legacy.renameTo(current)) {
			LOG.warn("Failed to rename legacy IDE data dir {} to {}", legacy, current);
		}
	}
}
