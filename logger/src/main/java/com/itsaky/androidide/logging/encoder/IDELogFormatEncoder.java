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

package com.itsaky.androidide.logging.encoder;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.Context;

/**
 * Encoder to format the log events in AndroidIDE.
 *
 * @author Akash Yadav
 */
public class IDELogFormatEncoder extends PatternLayoutEncoder {

  public IDELogFormatEncoder() {
    super();
    setLayout(new IDELogFormatLayout(false));
  }

  @Override
  public void setContext(Context context) {
    super.setContext(context);
    getLayout().setContext(context);
  }

  @Override
  public void start() {
    super.start();
    getLayout().start();
  }

  @Override
  public void stop() {
    super.stop();
    getLayout().stop();
  }
}
