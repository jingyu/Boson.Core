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

package io.bosonnetwork.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The {@code Date} header of an HTTP message, written and read the way RFC 9110 defines it.
 * <p>
 * Both ends of a Boson service need it: a server dates every answer by its own clock
 * ({@link ServerDateHandler}), and a client that issues its own access tokens reads that date to
 * learn how far its clock is from the server's
 * ({@link io.bosonnetwork.web.client.SelfIssuedAccessTokens}). They share this class so that one
 * definition of the format serves both.
 * <p>
 * This class deliberately mentions no HTTP type, so that it can be used by clients: {@code vertx-web}
 * is an optional dependency of this library, and only the server side has it.
 */
public final class HttpDate {
	// IMF-fixdate (RFC 9110, section 5.6.7): the preferred form, and the only one a server sends.
	private static final DateTimeFormatter IMF_FIXDATE =
			DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

	// The last formatted second, so that a busy server formats a date once a second rather than once a
	// response. Written without synchronization: two threads crossing a second boundary format the same
	// second twice, which costs nothing and yields the same string.
	private static volatile long cachedSecond = -1;
	private static volatile String cachedDate = "";

	private HttpDate() {
	}

	/**
	 * Formats an instant as an HTTP date. The value is truncated to the second, as the format has no
	 * room for anything finer.
	 *
	 * @param instant the instant
	 * @return the header value, such as {@code Sun, 20 Sep 2026 07:28:00 GMT}
	 */
	public static String format(Instant instant) {
		return IMF_FIXDATE.format(instant);
	}

	/**
	 * Returns the current time as an HTTP date, formatted at most once per second.
	 *
	 * @return the header value
	 */
	public static String current() {
		Instant now = Instant.now();
		long second = now.getEpochSecond();
		if (second != cachedSecond) {
			cachedDate = format(now);
			cachedSecond = second;
		}

		return cachedDate;
	}

	/**
	 * Reads an HTTP date. Only the IMF-fixdate form is accepted, which is what RFC 9110 requires a
	 * server to send; the two obsolete formats it allows a recipient to accept are not.
	 *
	 * @param value the header value, or {@code null}
	 * @return the instant, or {@code null} if there is none or it cannot be read
	 */
	public static @Nullable Instant parse(@Nullable String value) {
		if (value == null)
			return null;

		try {
			return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
