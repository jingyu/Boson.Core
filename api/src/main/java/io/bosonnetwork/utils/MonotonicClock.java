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

package io.bosonnetwork.utils;

/**
 * The clock for measuring elapsed time: intervals, timeouts, idle and retry timers, round trips.
 * <p>
 * Read from {@link System#nanoTime()}, so a step of the system clock - NTP correcting it, a user or a
 * virtual machine setting it - neither stretches an interval nor cuts one short, as it does when
 * {@link System#currentTimeMillis()} is used for the same purpose. That one stays the clock for dates:
 * anything persisted, sent to a peer or a client, shown to a person, or compared with such a date.
 * </p>
 * <p>
 * Counted from the wall-clock time this class was loaded (2020 at the earliest, whatever the wall clock
 * says), rather than from the arbitrary origin of
 * {@code nanoTime} - often the machine's boot - so the values are large and positive. A timestamp that
 * starts out as zero, or is reset to zero, to mean "never" therefore still reads as long ago: on a service
 * started at boot, a {@code nanoTime}-based zero would be only seconds old, and every
 * {@code now - last >= interval} check would take "never" for "just now".
 * </p>
 * <p>
 * The values are not dates. They drift from the wall clock by every step it has taken since this class
 * was loaded and mean nothing to another process, so never persist one, send one to a peer, or show one
 * as a date. {@link #elapsedMillis()} and {@link #elapsedNanos()} share the origin, so one value of each
 * read at the same moment agree: {@code elapsedNanos() / 1_000_000 == elapsedMillis()}.
 * </p>
 */
public final class MonotonicClock {
	// 2020-01-01T00:00:00Z. The origin only has to be large, not the real date, so a machine without a
	// battery-backed clock - a Raspberry Pi, a fresh VM image - that starts out believing it is 1970 and
	// is only set later by NTP still gets one.
	private static final long ORIGIN_FLOOR_MILLIS = 1_577_836_800_000L;

	private static final long ORIGIN_NANOS = System.nanoTime();
	private static final long ORIGIN_MILLIS = Math.max(System.currentTimeMillis(), ORIGIN_FLOOR_MILLIS);

	private MonotonicClock() {
	}

	/**
	 * Returns the current time in milliseconds.
	 *
	 * @return the current time in milliseconds; looks like an epoch time but is not a date.
	 */
	public static long elapsedMillis() {
		return ORIGIN_MILLIS + (System.nanoTime() - ORIGIN_NANOS) / 1_000_000;
	}

	/**
	 * Returns the current time in nanoseconds, for the rare timer that needs finer than a millisecond.
	 * <p>
	 * The same origin as {@link #elapsedMillis()}, so it fits a {@code long} until the year 2262.
	 * </p>
	 *
	 * @return the current time in nanoseconds; looks like an epoch time but is not a date.
	 */
	public static long elapsedNanos() {
		return ORIGIN_MILLIS * 1_000_000 + (System.nanoTime() - ORIGIN_NANOS);
	}
}
