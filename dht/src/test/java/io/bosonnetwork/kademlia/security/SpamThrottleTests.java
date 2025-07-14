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
		var throttle = new SpamThrottle(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		for (var i = 1; i < BURST_CAPACITY + 8; i++) {
			var limited = throttle.incrementAndCheck(addr);
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
		var throttle = new SpamThrottle(LIMIT_PER_SECOND, BURST_CAPACITY);
		var addr = InetAddress.getByName("192.168.8.1");

		for (var i = 1; i < BURST_CAPACITY + 8; i++) {
			var delay = throttle.incrementAndEstimateDelay(addr);
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
	public void testDecay() throws Exception {
		var addr = InetAddress.getByName("192.168.8.1");
		var throttle = new SpamThrottle(LIMIT_PER_SECOND, BURST_CAPACITY);

		for (var i = 1; i < BURST_CAPACITY * 8; i++) {
			var limited = throttle.incrementAndCheck(addr);
			assertFalse(limited);

			var delay = i < BURST_CAPACITY ? 1000 / BURST_CAPACITY + 1 : 1000 / LIMIT_PER_SECOND + 1;
			TimeUnit.MILLISECONDS.sleep(delay);
			throttle.decay();
		}
	}

	@Test
	public void testDecay2() throws Exception {
		var addr = InetAddress.getByName("192.168.8.1");
		var throttle = new SpamThrottle(LIMIT_PER_SECOND, BURST_CAPACITY);

		for (var i = 1; i < BURST_CAPACITY * 8; i++) {
			var delay = throttle.incrementAndEstimateDelay(addr);
			if (delay > 0)
				TimeUnit.MILLISECONDS.sleep(delay);

			throttle.decay();

			var limited = throttle.isLimitReached(addr);
			assertFalse(limited);
		}
	}
}