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

import com.itsaky.androidide.logging.encoder.IDELogFormatLayout;

import java.io.IOException;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * @author Akash Yadav
 */
public class StdErrAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private final PatternLayout layout = new IDELogFormatLayout(false);

  @Override
  public void start() {
    super.start();
    layout.start();
  }

  @Override
  public void setContext(Context context) {
    super.setContext(context);
    layout.setContext(context);
  }

  @Override
  public void stop() {
    super.stop();
    layout.stop();
  }

  @Override
  protected void append(ILoggingEvent eventObject) {
    if (!isStarted()) {
      return;
    }

    final var bytes = layout.doLayout(eventObject).getBytes();
    try {
      System.err.write(bytes);
    } catch (IOException e) {
      addError("Failed to write to stderr", e);
    }
  }
}
