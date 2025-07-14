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
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe rate limiter that restricts requests per IP address using a token bucket algorithm.
 * It allows a specified number of requests per second with a configurable burst capacity.
 * The throttle maintains request counts and periodically decays them based on elapsed time.
 */
public class SpamThrottle {
	private static final int DEFAULT_LIMIT_PER_SECOND = 32;
	private static final int DEFAULT_BURST_CAPACITY = 128;

	private final int limitPerSecond;
	private final int burstCapacity;

	private final Map<InetAddress, Integer> counter;
	private final AtomicLong lastDecayTime;

	/**
	 * Constructs a Throttle with custom limits.
	 *
	 * @param limitPerSecond Maximum requests allowed per second.
	 * @param burstCapacity Maximum burst requests allowed.
	 * @throws IllegalArgumentException if parameters are non-positive or burstCapacity is less than limitPerSecond.
	 */
	public SpamThrottle(int limitPerSecond, int burstCapacity) {
		if (limitPerSecond <= 0 || burstCapacity <= 0 || burstCapacity < limitPerSecond)
			throw new IllegalArgumentException("limitPerSecond and burstCapacity must be > 0 and burstCapacity must be >= limitPerSecond");

		this.limitPerSecond = limitPerSecond;
		this.burstCapacity = burstCapacity;

		this.counter = new ConcurrentHashMap<>();
		this.lastDecayTime = new AtomicLong(System.currentTimeMillis());
	}

	/**
	 * Constructs a Throttle with default limits (32 requests/sec, 128 burst).
	 */
	public SpamThrottle() {
		this(DEFAULT_LIMIT_PER_SECOND, DEFAULT_BURST_CAPACITY);
	}

	/**
	 * Creates a disabled SpamThrottle that allows all requests without restrictions.
	 *
	 * @return a new disabled SpamThrottle instance
	 */
	public static SpamThrottle disabled() {
		return new Disabled();
	}

	/**
	 * Increments the request count for an address and checks if the burst limit is reached.
	 *
	 * @param addr The IP address to track.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	public boolean incrementAndCheck(InetAddress addr) {
		decay(); // decay if needed

		int count = counter.compute(addr, (a, c) -> c == null ? 1 : Math.min(c + 1, burstCapacity));
		return count >= burstCapacity;
	}

	/**
	 * Increments the request count and estimates the delay (in milliseconds)
	 * needed before the next request is allowed.
	 *
	 * @param addr The IP address to check and increment.
	 * @return The estimated delay in milliseconds, or 0 if within limits.
	 */
	public int incrementAndEstimateDelay(InetAddress addr) {
		decay(); // decay if needed

		int count = counter.compute(addr, (a, c) -> c == null ? 1 : c + 1);
		if (count < burstCapacity)
			return 0;

		long now = System.currentTimeMillis();
		int decayDelay = (int) (1000 + lastDecayTime.get() - now);
		// IMPORTANT: +1 to fix that throttled by peer
		return decayDelay + ((count - burstCapacity + 1) * 1000 / limitPerSecond);
	}

	/**
	 * Decrements the request count for an address, removing it if it reaches zero.
	 *
	 * @param addr The IP address to decrement.
	 */
	public void decrement(InetAddress addr) {
		counter.computeIfPresent(addr, (a, c) -> c <= 1 ? null : c - 1);
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
		decay(); // decay if needed

		return counter.getOrDefault(addr, 0) >= burstCapacity;
	}

	/**
	 * Decays request counts for all IP addresses based on elapsed time since last decay.
	 * Removes entries with zero or negative counts after decay.
	 */
	public void decay() {
		long now = System.currentTimeMillis();
		long last = lastDecayTime.get();
		long interval = TimeUnit.MILLISECONDS.toSeconds(now - last);

		// use (last + interval * 1000) instead of now for randomization of decay time
		if (interval < 1 || !lastDecayTime.compareAndSet(last, last + interval * 1000))
			return;

		int delta = (int) (interval * limitPerSecond);
		// counter.entrySet().removeIf(entry -> entry.getValue() <= delta);
		// counter.replaceAll((k, v) -> v - delta);
		// ==>>
		// remove and update entries in a single pass — safely and efficiently
		Iterator<Map.Entry<InetAddress, Integer>> it = counter.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<InetAddress, Integer> entry = it.next();
			int value = entry.getValue();
			if (value <= delta)
				it.remove();
			else
				entry.setValue(value - delta);
		}
	}

	/**
	 * A disabled SpamThrottle implementation that allows all requests without restrictions.
	 * @see SpamThrottle
	 */
	private static class Disabled extends SpamThrottle {
		protected Disabled() {
			super(0, 0);
		}

		@Override
		public boolean incrementAndCheck(InetAddress addr) {
			return false;
		}

		@Override
		public int incrementAndEstimateDelay(InetAddress addr) {
			return 0;
		}

		@Override
		public void decrement(InetAddress addr) {
			// No-op
		}

		@Override
		public void clear(InetAddress addr) {
			// No-op
		}

		@Override
		public boolean isLimitReached(InetAddress addr) {
			return false;
		}

		@Override
		public void decay() {
			// No-op
		}
	}
}