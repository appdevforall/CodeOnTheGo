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

package com.itsaky.androidide.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itsaky.androidide.logging.utils.LogUtils;

/**
 * @author Akash Yadav
 */
public class JvmStdErrAppender extends StdErrAppender {

	public static final String PROP_JVM_STDERR_APPENDER_ENABLED = "ide.logging.jvmStdErrAppenderEnabled";

	private boolean jvmStdErrAppenderEnabled = true;

	@Override
	public void start() {
		jvmStdErrAppenderEnabled = Boolean.parseBoolean(
				System.getProperty(PROP_JVM_STDERR_APPENDER_ENABLED, "true"));
		jvmStdErrAppenderEnabled &= LogUtils.isJvm();
		super.start();
	}

	@Override
	protected void append(ILoggingEvent eventObject) {
		if (!jvmStdErrAppenderEnabled)
			return;
		super.append(eventObject);
	}
}
