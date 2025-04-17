/**
 * Copyright 2019 Anthony Trinh
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.itsaky.androidide.logging;

import android.util.Log;

import com.itsaky.androidide.logging.utils.LogUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.Abbreviator;
import ch.qos.logback.classic.pattern.ClassNameOnlyAbbreviator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * An appender that wraps the native Android logging mechanism (<i>logcat</i>); redirects all
 * logging requests to <i>logcat</i>
 * <p>
 * <b>Note:</b><br>
 * By default, this appender pushes all messages to <i>logcat</i> regardless of <i>logcat</i>'s own
 * filter settings (i.e., everything is printed). To disable this behavior and enable
 * filter-checking, use {@link #setCheckLoggable(boolean)}. See the Android Developer Guide for
 * details on adjusting the <i>logcat</i> filter.
 * <p>
 *
 * @author Fred Eisele
 * @author Anthony Trinh
 * @see <a href="http://developer.android.com/guide/developing/tools/adb.html#filteringoutput">ADB
 * Filtering Output</a>
 */
public class LogcatAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private final Abbreviator classNameAbbreviator = new ClassNameOnlyAbbreviator();
  private final PatternLayout messageLayout = new PatternLayout();
  private boolean checkLoggable = false;

  // AndroidIDE Changed: Appender is enabled only when running on Android.
  private boolean isAndroid = false;
  // AndroidIDE Changed

  public LogcatAppender() {
    this.messageLayout.setPattern("%msg%n");
  }

  /**
   * Checks that required parameters are set, and if everything is in order, activates this
   * appender.
   */
  @Override
  public void start() {
    this.isAndroid = !LogUtils.isJvm();

    if (!isAndroid) {
      addInfo("Appender [" + name + "] is not running on Android. Skipping init.");
      return;
    }

    super.start();
    this.messageLayout.start();
  }

  @Override
  public void setContext(Context context) {
    super.setContext(context);
    this.messageLayout.setContext(context);
  }

  @Override
  public void stop() {
    super.stop();
    this.messageLayout.stop();
  }

  @Override
  public boolean isStarted() {
    return super.isStarted() && isAndroid;
  }

  /**
   * Writes an event to Android's logging mechanism (logcat)
   *
   * @param event the event to be logged
   */
  @Override
  public void append(ILoggingEvent event) {

    if (!isStarted()) {
      return;
    }

    String tag = this.classNameAbbreviator.abbreviate(event.getLoggerName());

    switch (event.getLevel().levelInt) {
      case Level.ALL_INT:
      case Level.TRACE_INT:
        if (!checkLoggable || Log.isLoggable(tag, Log.VERBOSE)) {
          Log.v(tag, this.messageLayout.doLayout(event));
        }
        break;

      case Level.DEBUG_INT:
        if (!checkLoggable || Log.isLoggable(tag, Log.DEBUG)) {
          Log.d(tag, this.messageLayout.doLayout(event));
        }
        break;

      case Level.INFO_INT:
        if (!checkLoggable || Log.isLoggable(tag, Log.INFO)) {
          Log.i(tag, this.messageLayout.doLayout(event));
        }
        break;

      case Level.WARN_INT:
        if (!checkLoggable || Log.isLoggable(tag, Log.WARN)) {
          Log.w(tag, this.messageLayout.doLayout(event));
        }
        break;

      case Level.ERROR_INT:
        if (!checkLoggable || Log.isLoggable(tag, Log.ERROR)) {
          Log.e(tag, this.messageLayout.doLayout(event));
        }
        break;

      case Level.OFF_INT:
      default:
        break;
    }
  }

  /**
   * Sets whether to ask Android before logging a message with a specific tag and priority (i.e.,
   * calls {@code android.util.Log.html#isLoggable}).
   * <p>
   * See <a
   * href="http://developer.android.com/reference/android/util/Log.html#isLoggable">Log#isLoggable(java.lang.String,
   * int)</a>
   *
   * @param enable {@code true} to enable; {@code false} to disable
   */
  public void setCheckLoggable(boolean enable) {
    this.checkLoggable = enable;
  }

  /**
   * Gets the enable status of the <code>isLoggable()</code>-check that is called before logging
   * <p>
   * <a
   * href="http://developer.android.com/reference/android/util/Log.html#isLoggable">Log#isLoggable(java.lang.String,
   * int)</a>
   *
   * @return {@code true} if enabled; {@code false} otherwise
   */
  public boolean getCheckLoggable() {
    return this.checkLoggable;
  }

}