/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.utils.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ANSIConstants;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;


/**
 * A Logback converter that changes the console text color based on the logging level.
 * <p>
 * This class maps log levels (ERROR, WARN, INFO, TRACE) to specific ANSI color codes,
 * enabling colored output in supported terminals.
 * </p>
 */
public class HighlightingCompositeConverter extends ForegroundCompositeConverterBase<ILoggingEvent> {

	/**
	 * Returns the ANSI color code corresponding to the logging level of the given event.
	 *
	 * @param event the logging event
	 * @return the ANSI color code as a string
	 */
	@Override
	protected String getForegroundColorCode(ILoggingEvent event) {
		Level level = event.getLevel();
		return switch (level.toInt()) {
			case Level.ERROR_INT -> "38;5;196";
			case Level.WARN_INT -> "38;5;221";
			case Level.INFO_INT -> "38;5;40";
			case Level.TRACE_INT -> "38;5;245";
			default -> ANSIConstants.DEFAULT_FG;
		};
	}

}