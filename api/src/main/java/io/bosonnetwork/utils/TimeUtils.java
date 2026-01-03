/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.utils;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;

/**
 * Data and time related utility functions.
 */
@Deprecated
public class TimeUtils {
	/**
	 * Parse human friendly duration from a text string.
	 *
	 * Formats: &lt;number&gt;&lt;unit&gt;
	 * The supported units:
	 *  s - seconds
	 *  m - minutes
	 *  h - hours
	 *  d - days
	 *  w - weeks
	 *  M - months
	 *  y - years
	 *
	 * @param duration the duration string.
	 * @return the parsed {@code Duration} object.
	 * @throws DateTimeParseException if the text cannot be parsed to a duration.
	 */
	@Deprecated
	public static Duration parseDuration(CharSequence duration) throws DateTimeParseException {
		int idx = duration.length() - 1;
        final char specifier = duration.charAt(idx);
        final TemporalUnit unit = switch (specifier) {
			case 's' -> ChronoUnit.SECONDS;
			case 'm' -> ChronoUnit.MINUTES;
			case 'h' -> ChronoUnit.HOURS;
			case 'd' -> ChronoUnit.DAYS;
			case 'w' -> ChronoUnit.WEEKS;
			case 'M' -> ChronoUnit.MONTHS;
			case 'y' -> ChronoUnit.YEARS;
			default ->
					throw new DateTimeParseException("Can' parse duration, admitted only s, m, h, d, w, M, y", duration, idx);
		};

		try {
        	long number = Long.parseLong(duration, 0, idx, 10);
        	return Duration.ofSeconds(number * unit.getDuration().toSeconds());
        } catch (Exception e) {
        	throw new DateTimeParseException("Can' parse duration, not a number", duration, 0, e);
        }
	}
}