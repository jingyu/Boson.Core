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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A thread-safe Generic Cell Rate Algorithm style rate limiter that restricts requests per source
 * using a token bucket algorithm. It allows a specified number of requests per second with a
 * configurable burst capacity.
 *
 * <p>Counts are kept per {@link SourceKey} - IPv4 /32, IPv6 /64 - rather than per address, so that a sender
 * holding one IPv6 allocation cannot draw a fresh budget for every one of the 1.8e19 addresses in it. The
 * suspicious-node detector uses the same unit and the same string form of it; if the two disagreed, a sender
 * could sit inside one budget while exhausting the other.</p>
 *
 * <h2>How the bucket is stored</h2>
 *
 * <p>Each source is one {@code long}: the instant it would be back to zero debt, its <i>theoretical arrival
 * time</i>. A packet pushes that instant one {@link #emissionInterval} further out; the clock brings it back
 * at exactly one nanosecond per nanosecond, which is {@code limitPerSecond} packets per second. Debt is
 * therefore {@code tat - now}, and dividing it by the emission interval gives the packet count a plain
 * counter would have held - this is the same token bucket with its counter kept in time rather than in
 * integers, not an approximation of one.</p>
 *
 * <p>Storing it that way is what makes the throttle cost nothing to maintain. There is no periodic sweep to
 * bring every counter down, because no counter is ever stale: a source's budget refills because time passed,
 * not because this node visited it. One packet touches one entry, whatever the map holds. And because the
 * whole of a source's state is one value, every update is a single atomic remapping rather than a read
 * followed by a write, so a concurrent packet can neither be lost nor reset a flooder's debt to zero.</p>
 *
 * <p>{@link #decay()} remains, with the only job left: reclaiming entries whose debt has run out. That is
 * garbage collection rather than accounting, so it has no deadline, runs a bounded slice at a time, and is
 * skipped outright by a thread that finds another one already doing it - the one piece of state here that
 * is not a single atomic remapping is the cursor marking where the last slice stopped, and it is owned by
 * one thread at a time rather than shared.</p>
 */
public class DefaultSpamThrottle implements SpamThrottle {
	private static final int DEFAULT_LIMIT_PER_SECOND = 32;
	private static final int DEFAULT_BURST_CAPACITY = 512;

	/**
	 * How far past the burst ceiling a source is allowed to fall into debt.
	 * <p>
	 * A cap is needed in both directions. Without one, a sender that floods long enough owes hours and stays
	 * refused for hours - punishment bounded by nothing, charged to a {@link SourceKey} that a CGNAT makes
	 * shared; and {@link #incrementAndEstimateDelay} returns milliseconds in an {@code int}, which a large
	 * enough debt overflows into a negative delay. With the cap at the burst ceiling itself the estimate
	 * could never rise above zero, and the caller's own horizon - {@code RpcServer} fails rather than parks
	 * a call delayed past 10 seconds - would stop binding.
	 * </p>
	 * <p>
	 * So it sits above that horizon: the caller still decides what to do with a delay it does not like, and
	 * recovery from any flood, of any length, is bounded by the burst window plus this.
	 * </p>
	 */
	static final int MAX_DEBT_PAST_BURST = 15_000;

	/**
	 * Entries examined per {@link #decay()} call.
	 * <p>
	 * Reclamation is proportional to arrivals rather than to the clock, which is the right pairing: the
	 * traffic that fills the map is the traffic that empties it, and a node no one is talking to has nothing
	 * to reclaim. Small enough that no single packet pays a visible cost, large enough to outrun a flood
	 * that creates one entry per packet.
	 * </p>
	 */
	static final int RECLAIM_SLICE = 16;

	/** One packet's worth of debt, in nanoseconds. */
	private final long emissionInterval;
	/** The burst ceiling, in nanoseconds of debt: {@code burstCapacity} packets' worth. */
	private final long burstCeiling;
	/** The debt ceiling, past which debt stops accumulating. */
	private final long debtCeiling;

	/** Source key -> the instant that source is back to zero debt, on the {@link System#nanoTime} clock. */
	private final Map<String, Long> counter;

	/** Held by whichever thread is walking a reclaim slice. */
	private final AtomicBoolean reclaiming;

	/** Where the last reclaim slice stopped. Guarded by {@link #reclaiming}. */
	private Iterator<Map.Entry<String, Long>> reclaimCursor;

	/**
	 * Constructs a Throttle with custom limits.
	 *
	 * @param limitPerSecond Maximum requests allowed per second.
	 * @param burstCapacity Maximum burst requests allowed.
	 * @throws IllegalArgumentException if parameters are non-positive or burstCapacity is less than limitPerSecond.
	 */
	protected DefaultSpamThrottle(int limitPerSecond, int burstCapacity) {
		if (limitPerSecond <= 0 || burstCapacity <= 0 || burstCapacity < limitPerSecond)
			throw new IllegalArgumentException("limitPerSecond and burstCapacity must be > 0 and burstCapacity must be >= limitPerSecond");

		this.emissionInterval = TimeUnit.SECONDS.toNanos(1) / limitPerSecond;
		this.burstCeiling = emissionInterval * burstCapacity;
		this.debtCeiling = burstCeiling + TimeUnit.MILLISECONDS.toNanos(MAX_DEBT_PAST_BURST);

		this.counter = new ConcurrentHashMap<>();
		this.reclaiming = new AtomicBoolean();
	}

	/**
	 * Constructs a Throttle with default limits (32 requests/sec, 512 burst).
	 */
	protected DefaultSpamThrottle() {
		this(DEFAULT_LIMIT_PER_SECOND, DEFAULT_BURST_CAPACITY);
	}

	/**
	 * Charges one packet to a source and returns where that leaves it.
	 * <p>
	 * A source with no entry, or one whose debt has run out, starts from {@code now}: the clock has already
	 * given it everything it was owed, and nothing has to be subtracted to notice.
	 * </p>
	 *
	 * @param current the source's current arrival time, or null if it has no entry.
	 * @param now     the current reading of the nanosecond clock.
	 * @return the source's new arrival time.
	 */
	private long charge(Long current, long now) {
		// Differences rather than comparisons throughout: nanoTime has an arbitrary origin and wraps.
		long tat = (current == null || current - now < 0) ? now : current;
		long charged = tat + emissionInterval;
		long ceiling = now + debtCeiling;
		return charged - ceiling > 0 ? ceiling : charged;
	}

	/**
	 * The debt a source is currently carrying, in nanoseconds, or 0 if it has none.
	 *
	 * @param tat the source's arrival time, or null if it has no entry.
	 * @param now the current reading of the nanosecond clock.
	 * @return the debt, never negative.
	 */
	private static long debt(Long tat, long now) {
		if (tat == null)
			return 0;

		long debt = tat - now;
		return debt > 0 ? debt : 0;
	}

	@Override
	public boolean incrementAndCheck(String addr) {
		return charged(SourceKey.of(addr));
	}

	/**
	 * Increments the request count for an address and checks if the burst limit is reached.
	 *
	 * @param addr The IP address to track.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	@Override
	public boolean incrementAndCheck(InetAddress addr) {
		return charged(sourceKey(addr));
	}

	/**
	 * Charges one packet to an already-reduced source and reports whether that puts it over the burst.
	 *
	 * @param source the source key to charge.
	 * @return true if the burst limit is exceeded, false otherwise.
	 */
	private boolean charged(String source) {
		reclaim();

		long now = System.nanoTime();
		long tat = counter.compute(source, (a, t) -> charge(t, now));
		// Strictly past the ceiling, so a burst of exactly burstCapacity packets is admitted rather than
		// one short of it.
		return tat - now > burstCeiling;
	}

	/**
	 * Increments the request count and estimates the delay (in milliseconds)
	 * needed before the next request is allowed.
	 *
	 * @param addr The IP address to check and increment.
	 * @return The estimated delay in milliseconds, or 0 if within limits.
	 */
	@Override
	public int incrementAndEstimateDelay(InetAddress addr) {
		reclaim();

		long now = System.nanoTime();
		long tat = counter.compute(sourceKey(addr), (a, t) -> charge(t, now));

		long over = (tat - now) - burstCeiling;
		if (over <= 0)
			return 0;

		// One emission interval past the ceiling, so that waiting this long leaves the caller admitted
		// rather than exactly at the threshold it was refused on.
		return (int) TimeUnit.NANOSECONDS.toMillis(over + emissionInterval);
	}

	/**
	 * Decrements the request count for an address, removing it if it reaches zero.
	 * <p>
	 * A source with no debt keeps none: the refund is dropped rather than banked. Credit carried forward
	 * would let a node that answers our calls arrive with a budget larger than the burst, which is the
	 * bypass this refund was introduced to replace.
	 * </p>
	 *
	 * @param addr The IP address to decrement.
	 */
	@Override
	public void decrement(InetAddress addr) {
		long now = System.nanoTime();
		counter.computeIfPresent(sourceKey(addr), (a, t) -> {
			long refunded = t - emissionInterval;
			return refunded - now > 0 ? refunded : null;
		});
	}

	/**
	 * Clears the request count for an address.
	 *
	 * @param addr The IP address to clear.
	 */
	@Override
	public void clear(InetAddress addr) {
		counter.remove(sourceKey(addr));
	}

	/**
	 * Clears all request counts.
	 */
	@Override
	public void clear() {
		// The reclaim cursor is deliberately left alone: writing it here would be a write to guarded state
		// from outside the guard, and it needs no help - a cursor over an emptied map reports nothing left
		// on the next slice and is replaced there.
		counter.clear();
	}

	/**
	 * Checks if the address has reached or exceeded the burst limit.
	 *
	 * @param addr The IP address to check.
	 * @return true if the burst limit is reached or exceeded, false otherwise.
	 */
	@Override
	public boolean isLimitReached(InetAddress addr) {
		long now = System.nanoTime();
		return debt(counter.get(sourceKey(addr)), now) >= burstCeiling;
	}

	/**
	 * Reclaims entries for sources whose debt has run out.
	 * <p>
	 * Nothing here affects what the throttle allows - a source's budget refills with the clock whether or
	 * not its entry is ever visited again - so this is memory reclamation with no deadline, and it runs a
	 * bounded slice rather than a full pass. Entries are removed on the value they were read at, so a
	 * source that sends a packet while the slice is walking keeps the debt that packet bought it.
	 * </p>
	 */
	@Override
	public void decay() {
		reclaim();
	}

	private void reclaim() {
		// One thread at a time, and one that finds another already sweeping skips its slice rather than
		// waiting for it - which is free to do, because reclamation has no deadline and the traffic that
		// fills the map will be back. The exclusion is what matters: a ConcurrentHashMap iterator tolerates
		// concurrent writers but not a second thread advancing it, and the fault would surface as an
		// exception thrown out of the packet-receive path. Taking the flag is also what publishes the
		// cursor from the thread that ran the previous slice.
		if (counter.isEmpty() || !reclaiming.compareAndSet(false, true))
			return;

		try {
			long now = System.nanoTime();
			Iterator<Map.Entry<String, Long>> cursor = reclaimCursor;

			for (int i = 0; i < RECLAIM_SLICE; i++) {
				if (cursor == null || !cursor.hasNext()) {
					cursor = counter.entrySet().iterator();
					if (!cursor.hasNext()) {
						cursor = null;
						break;
					}
				}

				Map.Entry<String, Long> entry = cursor.next();
				if (entry.getValue() - now <= 0)
					counter.remove(entry.getKey(), entry.getValue());
			}

			reclaimCursor = cursor;
		} finally {
			reclaiming.set(false);
		}
	}

	/**
	 * The key a source is counted under, in the same string form the suspicious-node detector uses.
	 *
	 * @param addr the address to reduce.
	 * @return the source key.
	 */
	private static String sourceKey(InetAddress addr) {
		return SourceKey.of(addr).getHostAddress();
	}

	/**
	 * The number of sources currently held. For tests: nothing about the throttle's behaviour depends on
	 * this, only its footprint does.
	 *
	 * @return the number of entries held.
	 */
	int size() {
		return counter.size();
	}
}
