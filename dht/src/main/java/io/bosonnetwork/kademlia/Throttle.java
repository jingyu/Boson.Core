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

package io.bosonnetwork.kademlia;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Throttle(rate limiter) that restricts requests per IP address based on a token bucket algorithm.
 * Allows a specified number of requests per second with a burst capacity.
 */
public class Throttle {
	private static final int DEFAULT_LIMIT_PER_SECOND = 32;
	private static final int DEFAULT_BURST_CAPACITY = 128;

	private final int limitPerSecond;
	private final int burstCapacity;

	private final Map<InetAddress, Integer> counter = new ConcurrentHashMap<>();
	private final AtomicLong lastDecayTime = new AtomicLong(System.currentTimeMillis());

	/**
	 * Constructs a Throttle with custom limits.
	 *
	 * @param limitPerSecond Maximum requests allowed per second.
	 * @param burstCapacity Maximum burst requests allowed.
	 * @throws IllegalArgumentException if parameters are non-positive or burstCapacity is less than limitPerSecond.
	 */
	protected Throttle(int limitPerSecond, int burstCapacity) {
		if (limitPerSecond <= 0 || burstCapacity <= 0 || burstCapacity < limitPerSecond)
			throw new IllegalArgumentException("limitPerSecond and burstCapacity must be > 0 and burstCapacity must be >= limitPerSecond");

		this.limitPerSecond = limitPerSecond;
		this.burstCapacity = burstCapacity;
	}

	/**
	 * Constructs a Throttle with default limits (32 requests/sec, 128 burst).
	 */
	protected Throttle() {
		this(DEFAULT_LIMIT_PER_SECOND, DEFAULT_BURST_CAPACITY);
	}

	/**
	 * Creates a new Throttle with default limits (32 requests/sec, 128 burst).
	 * @return A new Throttle.
	 */
	public static Throttle enabled() {
		return new Throttle();
	}

	/**
	 * Creates a new Throttle with custom limits.
	 * @param limitPerSecond Maximum requests allowed per second.
	 * @param burstCapacity Maximum burst requests allowed.
	 * @return A new Throttle.
	 * @throws IllegalArgumentException if parameters are non-positive or burstCapacity is less than limitPerSecond.
	 */
	public static Throttle enabled(int limitPerSecond, int burstCapacity) {
		return new Throttle(limitPerSecond, burstCapacity);
	}

	/**
	 * Creates a new Throttle that is disabled.
	 * @return A new Throttle.
	 */
	public static Throttle disabled() {
		return new Disabled();
	}

	/**
	 * Increments the request count for an address and checks if the burst limit is reached.
	 *
	 * @param addr The IP address to track.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	public boolean incrementAndCheck(InetAddress addr) {
		int count = counter.compute(addr, (a, c) -> c == null ? 1 : Math.min(c + 1, burstCapacity));
		return count >= burstCapacity;
	}

	/**
	 * Decrements the request count for an address, removing it if it reaches zero.
	 *
	 * @param addr The IP address to decrement.
	 */
	public void decrement(InetAddress addr) {
		counter.compute(addr, (a, c) -> c == null || c == 1 ? null : c - 1);
	}

	/**
	 * Clears the request count for an address.
	 *
	 * @param addr The IP address to clear.
	 */
	public void clear(InetAddress addr) {
		counter.remove(addr);
	}

	/**
	 * Checks if the address has reached or exceeded the burst limit.
	 *
	 * @param addr The IP address to check.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	public boolean isLimitReached(InetAddress addr) {
		return counter.getOrDefault(addr, 0) >= burstCapacity;
	}

	/**
	 * Increments the request count and estimates the delay (in milliseconds)
	 * needed before the next request is allowed.
	 *
	 * @param addr The IP address to check and increment.
	 * @return The estimated delay in milliseconds, or 0 if within limits.
	 */
	public int incrementAndEstimateDelay(InetAddress addr) {
		int count = counter.compute(addr, (a, c) -> c == null ? 1 : c + 1);
		if (count < burstCapacity)
			return 0;
		// IMPORTANT: +1 to fix that throttled by peer
		return (count - burstCapacity + 1) * 1000 / limitPerSecond;
	}

	/**
	 * Decays request counts based on elapsed time, reducing counts proportionally
	 * to the rate limit and removing entries with zero or negative counts.
	 */
	public void decay() {
		long now = System.currentTimeMillis();
		long last = lastDecayTime.get();
		long interval = TimeUnit.MILLISECONDS.toSeconds(now - last);

		if (interval < 1)
			return;
		if (!lastDecayTime.compareAndSet(last, last + interval * 1000))
			return;

		int delta = (int) (interval * limitPerSecond);
		counter.entrySet().removeIf(entry -> entry.getValue() <= delta);
		counter.replaceAll((k, v) -> v - delta);
	}

	/**
	 * Disabled Throttle.
	 * @see Throttle
	 */
	private static class Disabled extends Throttle {
		protected Disabled() {
			super(0, 0);
		}

		@Override
		public boolean incrementAndCheck(InetAddress addr) {
			return false;
		}

		@Override
		public void decrement(InetAddress addr) {
		}

		@Override
		public void clear(InetAddress addr) {
		}

		@Override
		public boolean isLimitReached(InetAddress addr) {
			return false;
		}

		@Override
		public int incrementAndEstimateDelay(InetAddress addr) {
			return 0;
		}

		@Override
		public void decay() {
		}
	}
}