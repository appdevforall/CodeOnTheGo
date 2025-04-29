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

package com.itsaky.androidide.logging.utils;

import com.itsaky.androidide.utils.LogTagUtils;

import java.util.regex.Pattern;

/**
 * @author Akash Yadav
 */
public class LogUtils {

    public static final int MAX_TAG_LENGTH = 23;
    public static final String PATTERN_LAYOUT_MESSAGE_PATTERN = "%msg%n";

    public static boolean isJvm() {
        try {
            // If we're in a testing environment
            Class.forName("org.junit.runners.JUnit4");
            return true;
        } catch (ClassNotFoundException e) {
            // ignored
        }

        try {
            Class.forName("android.content.Context");
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    public static String processLogTag(String tag) {
        if (tag == null) {
            return null;
        }

        final var regex = "[^a-z-A-Z0-9_.]";
        if (Pattern.compile(regex).matcher(tag).find()) {
            tag = tag.replaceAll(regex, "_");
        }

        return LogTagUtils.trimTagIfNeeded(tag, MAX_TAG_LENGTH);
    }

    public static String getPatternLayoutVerbosePattern(boolean omitMessage) {
        return "%d{dd-MM HH:mm:ss.SS} %5level [%thread] %logger:" + (omitMessage ? "" : " %msg") + "%n";
    }
}
