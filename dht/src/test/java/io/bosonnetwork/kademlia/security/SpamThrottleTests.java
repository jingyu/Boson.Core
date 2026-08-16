package io.bosonnetwork.kademlia.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class SpamThrottleTests {
	private static final int LIMIT_PER_SECOND = 16;
	private static final int BURST_CAPACITY = 48;

	/** One packet's worth of budget, in milliseconds: the interval the throttle refills at. */
	private static final int EMISSION_INTERVAL = 1000 / LIMIT_PER_SECOND;

	@Test
	public void testBurstIsAdmittedInFull() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		// Sent back-to-back, so the clock refunds nothing worth counting and the burst allowance is the
		// only thing deciding. burstCapacity is what the throttle documents it allows, so all of them go
		// through and the one after is the first to be refused.
		for (var i = 1; i <= BURST_CAPACITY; i++)
			assertFalse(throttle.incrementAndCheck(addr), "throttled early, at " + i);

		assertTrue(throttle.incrementAndCheck(addr), "the burst outlasted its capacity");
	}

	@Test
	public void testDelayEstimateGrowsPastCapacity() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		for (var i = 1; i <= BURST_CAPACITY; i++)
			assertEquals(0, throttle.incrementAndEstimateDelay(addr), "delayed inside the burst, at " + i);

		// Past the ceiling the estimate is how long this sender has to wait to be admitted, which is its
		// debt over the ceiling plus the one packet it is asking for.
		for (var i = BURST_CAPACITY + 1; i < BURST_CAPACITY + 8; i++) {
			var expected = (i - BURST_CAPACITY + 1) * 1000 / LIMIT_PER_SECOND;
			var delay = throttle.incrementAndEstimateDelay(addr);
			// Real time passes between iterations and is genuinely refunded, so the estimate can come in
			// slightly under. It must never come in over.
			assertTrue(delay <= expected && delay >= expected - 10,
					"expected about " + expected + "ms at " + i + ", got " + delay);
		}
	}

	@Test
	public void testSustainedAtTheLimitIsNeverThrottled() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		// A sender holding exactly the configured rate spends its budget as fast as it earns it, so it can
		// keep going forever without ever touching the burst allowance.
		for (var i = 1; i < LIMIT_PER_SECOND * 2; i++) {
			assertFalse(throttle.incrementAndCheck(addr), "throttled at the limit rate, at " + i);
			TimeUnit.MILLISECONDS.sleep(EMISSION_INTERVAL + 1);
		}
	}

	@Test
	public void testQuietTimeRefundsContinuously() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		for (var i = 1; i <= BURST_CAPACITY; i++)
			assertFalse(throttle.incrementAndCheck(addr), "throttled early, at " + i);

		// With no quiet time at all, the next one is refused.
		assertTrue(throttle.incrementAndCheck(addr), "the burst outlasted its capacity");

		// The property a whole-second decay could not express: this much quiet is refunded as it passes,
		// where the old one returned nothing at all until the next tick and a whole second's worth at it.
		// The refused packet above was still charged, so the debt starts one interval over the ceiling.
		TimeUnit.MILLISECONDS.sleep(EMISSION_INTERVAL * 4L);

		assertFalse(throttle.incrementAndCheck(addr), "the first refund did not arrive");
		assertFalse(throttle.incrementAndCheck(addr), "the second refund did not arrive");
	}

	@Test
	public void testWaitingTheEstimatedDelayBuysAdmission() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		for (var i = 1; i < BURST_CAPACITY + 20; i++) {
			var delay = throttle.incrementAndEstimateDelay(addr);
			if (delay > 0)
				TimeUnit.MILLISECONDS.sleep(delay);

			// The estimate has to leave the caller admitted rather than exactly at the threshold it was
			// just refused on, which is what the extra interval in it is for.
			assertFalse(throttle.isLimitReached(addr), "still limited after waiting the estimate, at " + i);
		}
	}

	@Test
	public void testRefundCannotBankCredit() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		// The RPC receive path refunds a packet whenever one turns out to answer a call it made, and the
		// refund is a decrement of a counter it did not itself increment. Decrementing an address with no
		// count must therefore be a no-op rather than credit carried forward: if it accumulated, a node
		// that answers our calls would arrive with a budget larger than the burst, which is the bypass the
		// refund replaced, reintroduced from the other end.
		for (var i = 0; i < BURST_CAPACITY * 4; i++)
			throttle.decrement(addr);

		for (var i = 1; i <= BURST_CAPACITY; i++)
			assertFalse(throttle.incrementAndCheck(addr), "throttled early at " + i);

		assertTrue(throttle.incrementAndCheck(addr), "the burst outlasted its capacity");
	}

	@Test
	public void testDebtIsCappedSoRecoveryIsBounded() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		// However long a flood runs, what it owes stops growing. Uncapped, this many packets would owe
		// hours and stay refused for hours - punishment bounded by nothing, on a unit a CGNAT shares - and
		// the millisecond estimate would overflow its int into a negative delay.
		var delay = 0;
		for (var i = 0; i < 100_000; i++)
			delay = throttle.incrementAndEstimateDelay(addr);

		assertTrue(delay > 0, "a flood this size should be delayed");
		assertTrue(delay <= DefaultSpamThrottle.MAX_DEBT_PAST_BURST + EMISSION_INTERVAL + 1,
				"debt was not capped: " + delay + "ms");
	}

	@Test
	public void testReclamationDropsSpentSourcesOnly() throws Exception {
		var throttle = (DefaultSpamThrottle) SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);

		// One packet each from many sources - the shape of a flood with source diversity, and what used to
		// accumulate for a whole second before a sweep took it away.
		var spent = 64;
		for (var i = 0; i < spent; i++)
			throttle.incrementAndCheck("10.0.0." + i);

		// And one source deep in debt, which must survive: reclamation is memory, not amnesty.
		var heavy = InetAddress.getByName("192.168.8.1");
		for (var i = 0; i < BURST_CAPACITY * 2; i++)
			throttle.incrementAndCheck(heavy);

		assertEquals(spent + 1, throttle.size());

		// One packet buys one emission interval of debt, so that is how long the spent ones live.
		TimeUnit.MILLISECONDS.sleep(EMISSION_INTERVAL * 2L);

		// A bounded slice per call, so it takes a few calls to walk them - which is the point: no single
		// packet ever pays for the whole map.
		for (var i = 0; i < (spent / DefaultSpamThrottle.RECLAIM_SLICE + 1) * 4; i++)
			throttle.decay();

		assertEquals(1, throttle.size(), "spent sources were not reclaimed");
		assertTrue(throttle.isLimitReached(heavy), "the source still in debt was reclaimed");
	}

	@Test
	public void testAddressAndLiteralFormsShareOneBudget() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		// The receive path counts a literal off the socket and the refund counts a resolved address; if the
		// two keyed differently, a sender would hold two budgets and the refund would credit neither.
		for (var i = 1; i <= BURST_CAPACITY; i++)
			assertFalse(throttle.incrementAndCheck(i % 2 == 0 ? "192.168.8.1" : addr.getHostAddress()),
					"throttled early, at " + i);

		// On one shared budget this is the packet past the ceiling. On two, each form would be holding
		// half a burst and this would sail through - and so would the refund path, crediting neither.
		assertTrue(throttle.incrementAndCheck(addr), "the two forms were counted separately");
	}

	@Test
	public void testOneIpv6AllocationIsOneSource() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);

		// Different addresses, one /64 - the whole reason the throttle counts SourceKeys and not addresses.
		for (var i = 1; i <= BURST_CAPACITY; i++)
			assertFalse(throttle.incrementAndCheck("2001:db8:1:2::" + Integer.toHexString(i)),
					"throttled early, at " + i);

		assertTrue(throttle.incrementAndCheck("2001:db8:1:2::ffff"),
				"a fresh address in the same allocation drew a fresh budget");
	}
}
