package io.bosonnetwork.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

public class TimeUtilsTests {
	@Test
	void parseDurationTest() {
		Duration duration = TimeUtils.parseDuration("180s");
		assertEquals(180, duration.toSeconds());

		duration = TimeUtils.parseDuration("5m");
		assertEquals(5 * ChronoUnit.MINUTES.getDuration().toSeconds(), duration.toSeconds());

		duration = TimeUtils.parseDuration("6h");
		assertEquals(6 * ChronoUnit.HOURS.getDuration().toSeconds(), duration.toSeconds());

		duration = TimeUtils.parseDuration("8d");
		assertEquals(8 * ChronoUnit.DAYS.getDuration().toSeconds(), duration.toSeconds());

		duration = TimeUtils.parseDuration("9w");
		assertEquals(9 * ChronoUnit.WEEKS.getDuration().toSeconds(), duration.toSeconds());

		duration = TimeUtils.parseDuration("3M");
		assertEquals(3 * ChronoUnit.MONTHS.getDuration().toSeconds(), duration.toSeconds());

		duration = TimeUtils.parseDuration("2y");
		assertEquals(2 * ChronoUnit.YEARS.getDuration().toSeconds(), duration.toSeconds());

		DateTimeParseException e = assertThrows(DateTimeParseException.class, () -> TimeUtils.parseDuration("1088t"));
		assertEquals("Can' parse duration, admitted only s, m, h, d, w, M, y", e.getMessage());
		assertEquals(4, e.getErrorIndex());
		assertEquals("1088t", e.getParsedString());

		e = assertThrows(DateTimeParseException.class, () -> TimeUtils.parseDuration("nanM"));
		assertEquals("Can' parse duration, not a number", e.getMessage());
		assertEquals(0, e.getErrorIndex());
		assertEquals("nanM", e.getParsedString());


	}
}
