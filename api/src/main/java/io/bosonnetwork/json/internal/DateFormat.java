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

package io.bosonnetwork.json.internal;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * Utility class for date formatting.
 */
public class DateFormat {

	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
	@SuppressWarnings("SpellCheckingInspection")
	private static final String ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	@SuppressWarnings("SpellCheckingInspection")
	private static final String ISO_8601_WITH_MILLISECONDS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

	/**
	 * Returns the default date and time format for serializing and deserializing {@link java.util.Date} objects.
	 * <p>
	 * This format uses the ISO8601 pattern {@code yyyy-MM-dd'T'HH:mm:ss'Z'} in UTC timezone.
	 *
	 * @return the {@code DateFormat} object for ISO8601 date formatting.
	 */
	public static java.text.DateFormat getDefault() {
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601);
		dateFormat.setTimeZone(UTC);
		return dateFormat;
	}

	/**
	 * Returns a failback date and time format for deserializing {@link java.util.Date} objects.
	 * <p>
	 * This format uses the ISO8601 pattern with milliseconds: {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC timezone.
	 * Used if parsing with {@link #getDefault()} fails.
	 *
	 * @return the {@code DateFormat} object for ISO8601 date formatting with milliseconds.
	 */
	public static java.text.DateFormat getFailback() {
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601_WITH_MILLISECONDS);
		dateFormat.setTimeZone(UTC);
		return dateFormat;
	}
}