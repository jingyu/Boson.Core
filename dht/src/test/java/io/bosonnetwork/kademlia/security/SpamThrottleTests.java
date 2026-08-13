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

	@Test
	public void testIncrementAndCheck() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		for (var i = 1; i < BURST_CAPACITY + 8; i++) {
			System.out.print("Throttle - incrementAndCheck: " + i + " ... ");
			var limited = throttle.incrementAndCheck(addr);
			System.out.println(limited);
			if (i < BURST_CAPACITY)
				assertFalse(limited);
			else
				assertTrue(limited);

			TimeUnit.MILLISECONDS.sleep(10);
			throttle.decay();
		}
	}

	@Test
	public void testIncrementAndEstimateDelay() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		for (var i = 1; i < BURST_CAPACITY + 8; i++) {
			System.out.print("Throttle - incrementAndEstimateDelay: " + i + " ... ");
			var delay = throttle.incrementAndEstimateDelay(addr);
			System.out.println(delay);
			if (i < BURST_CAPACITY) {
				assertEquals(0, delay);
			} else {
				int expected = (i - BURST_CAPACITY + 1) * 1000 / LIMIT_PER_SECOND;
				assertTrue(expected <= delay);
			}

			TimeUnit.MILLISECONDS.sleep(10);
			throttle.decay();
		}
	}

	@Test
	public void testDecrementCannotBankCredit() throws Exception {
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		// The RPC receive path refunds a packet whenever one turns out to answer a call it made, and the
		// refund is a decrement of a counter it did not itself increment. Decrementing an address with no
		// count must therefore be a no-op rather than credit carried forward: if it accumulated, a node
		// that answers our calls would arrive with a budget larger than the burst, which is the bypass the
		// refund replaced, reintroduced from the other end.
		for (var i = 0; i < BURST_CAPACITY * 4; i++)
			throttle.decrement(addr);

		for (var i = 1; i < BURST_CAPACITY; i++)
			assertFalse(throttle.incrementAndCheck(addr), "throttled early at " + i);

		assertTrue(throttle.incrementAndCheck(addr), "the burst outlasted its capacity");
	}

	@Test
	public void testDecay() throws Exception {
		var addr = InetAddress.getByName("192.168.8.1");
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);

		for (var i = 1; i < BURST_CAPACITY * 8; i++) {
			System.out.print("Throttle - incrementAndCheck: " + i + " ... ");
			var limited = throttle.incrementAndCheck(addr);
			System.out.println(limited);
			assertFalse(limited);

			var delay = i < BURST_CAPACITY ? 1000 / BURST_CAPACITY + 1 : 1000 / LIMIT_PER_SECOND + 1;
			TimeUnit.MILLISECONDS.sleep(delay);
			throttle.decay();
		}
	}

	@Test
	public void testDecay2() throws Exception {
		var addr = InetAddress.getByName("192.168.8.1");
		var throttle = SpamThrottle.create(LIMIT_PER_SECOND, BURST_CAPACITY);

		for (var i = 1; i < BURST_CAPACITY * 8; i++) {
			System.out.print("Throttle - incrementAndEstimateDelay: " + i + " ... ");
			var delay = throttle.incrementAndEstimateDelay(addr);
			System.out.println(delay);
			if (delay > 0)
				TimeUnit.MILLISECONDS.sleep(delay);

			throttle.decay();

			var limited = throttle.isLimitReached(addr);
			assertFalse(limited);
		}
	}
}