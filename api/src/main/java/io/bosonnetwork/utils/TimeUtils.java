package io.bosonnetwork.utils;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;

/**
 * Data and time related utility functions.
 */
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