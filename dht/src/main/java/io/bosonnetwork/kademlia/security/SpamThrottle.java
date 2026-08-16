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

package io.bosonnetwork.kademlia.security;

import java.net.InetAddress;

/**
 * Interface for throttling requests based on IP address.
 */
public interface SpamThrottle {
	/**
	 * Create a Throttle with custom limits.
	 *
	 * @param limitPerSecond Maximum requests allowed per second.
	 * @param burstCapacity Maximum burst requests allowed.
	 * @throws IllegalArgumentException if parameters are non-positive or burstCapacity is less than limitPerSecond.
	 */
	static SpamThrottle create(int limitPerSecond, int burstCapacity) {
		return new DefaultSpamThrottle(limitPerSecond, burstCapacity);
	}

	/**
	 * Create a Throttle with default limits (32 requests/sec, 512 burst).
	 */
	static SpamThrottle create() {
		return new DefaultSpamThrottle();
	}

	/**
	 * Creates a disabled SpamThrottle that allows all requests without restrictions.
	 *
	 * @return a new disabled SpamThrottle instance
	 */
	static SpamThrottle disabled() {
		return new DisabledSpamThrottle();
	}

	/**
	 * Increments the request count for a host address in literal form, and checks if the burst limit is
	 * reached.
	 * <p>
	 * This is the form the packet-receive path uses, and the cheaper of the two: an address arrives from the
	 * socket as a string, and {@link SourceKey#of(String)} reduces an IPv4 literal to its accountable unit
	 * without parsing it.
	 * </p>
	 *
	 * @param addr The host address, in literal form, to track.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	boolean incrementAndCheck(String addr);

	/**
	 * Increments the request count for an address and checks if the burst limit is reached.
	 *
	 * @param addr The IP address to track.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	boolean incrementAndCheck(InetAddress addr);

	/**
	 * Increments the request count and estimates the delay (in milliseconds)
	 * needed before the next request is allowed.
	 *
	 * @param addr The IP address to check and increment.
	 * @return The estimated delay in milliseconds, or 0 if within limits.
	 */
	int incrementAndEstimateDelay(InetAddress addr);

	/**
	 * Decrements the request count for an address, removing it if it reaches zero.
	 *
	 * @param addr The IP address to decrement.
	 */
	void decrement(InetAddress addr);

	/**
	 * Clears the request count for an address.
	 *
	 * @param addr The IP address to clear.
	 */
	void clear(InetAddress addr);

	/**
	 * Clears all request counts.
	 */
	void clear();

	/**
	 * Checks if the address has reached or exceeded the burst limit.
	 *
	 * @param addr The IP address to check.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	boolean isLimitReached(InetAddress addr);

	/**
	 * Reclaims the entries of sources that have run out of debt.
	 * <p>
	 * Decay itself is not something an implementation has to be asked to do: a source's budget refills with
	 * the passage of time, whether or not anything visits its entry. So this affects only how much the
	 * throttle is holding, never what it allows, and an implementation is free to do a bounded amount of it
	 * per call. Callers do not need to invoke it - every counting method already reclaims as it goes.
	 * </p>
	 */
	void decay();
}