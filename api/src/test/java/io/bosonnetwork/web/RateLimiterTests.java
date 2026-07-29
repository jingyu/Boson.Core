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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import io.github.bucket4j.TimeMeter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Signature;

/**
 * Unit tests for {@link RateLimiter}, driven by a controllable clock so refill behavior can be
 * asserted exactly rather than by sleeping.
 */
@DisplayName("RateLimiter tests")
class RateLimiterTests {
	/** A clock the test advances by hand. */
	private static class TestClock implements TimeMeter {
		private long nanos = System.currentTimeMillis() * 1_000_000L;

		@Override
		public long currentTimeNanos() {
			return nanos;
		}

		@Override
		public boolean isWallClockBased() {
			return true;
		}

		void advance(Duration duration) {
			nanos += duration.toNanos();
		}
	}

	private static Id randomUser() {
		return Id.of(Signature.KeyPair.random().publicKey().bytes());
	}

	private static RateLimiter.Scope scope(int perMinute, int perHour, int perDay) {
		return new RateLimiter.Scope("user", new RateLimitPolicy(perMinute, perHour, perDay));
	}

	private static RateLimiter.Scope addressScope(int perMinute) {
		return new RateLimiter.Scope("address", RateLimitPolicy.perMinute(perMinute));
	}

	private static RateLimiter limiter() {
		return RateLimiter.builder().clock(new TestClock()).build();
	}

	private static RateLimiter limiter(TestClock clock) {
		return RateLimiter.builder().clock(clock).build();
	}

	/** A plan feature block in the shape the authorizer hands out: a nested rateLimit map. */
	private static Map<String, Object> features(Object... kv) {
		if (kv.length == 0)
			return Map.of();

		Map<String, Object> rateLimit = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2)
			rateLimit.put((String) kv[i], kv[i + 1]);

		return Map.of("rateLimit", rateLimit);
	}

	// -------------------------------------------------------------------------
	// Per-user limits
	// -------------------------------------------------------------------------

	@Test
	void testConsumesUpToCapacityThenRejects() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(3, 0, 0);
		Id user = randomUser();

		assertTrue(limiter.checkUser(scope, user, Map.of(), 1).allowed());
		assertTrue(limiter.checkUser(scope, user, Map.of(), 1).allowed());

		RateLimiter.Decision last = limiter.checkUser(scope, user, Map.of(), 1);
		assertTrue(last.allowed());
		assertEquals(0, last.remainingTokens());

		RateLimiter.Decision rejected = limiter.checkUser(scope, user, Map.of(), 1);
		assertFalse(rejected.allowed());
		assertEquals(0, rejected.remainingTokens());
		// Never report 0: that would invite an immediate retry that is certain to fail.
		assertTrue(rejected.retryAfterSeconds() >= 1);
	}

	@Test
	void testRefillsOverTime() {
		TestClock clock = new TestClock();
		RateLimiter limiter = limiter(clock);
		RateLimiter.Scope scope = scope(60, 0, 0);
		Id user = randomUser();

		for (int i = 0; i < 60; i++)
			assertTrue(limiter.checkUser(scope, user, Map.of(), 1).allowed());
		assertFalse(limiter.checkUser(scope, user, Map.of(), 1).allowed());

		// Greedy refill: 60 tokens a minute is one a second, available continuously rather than
		// all at once when the window rolls over.
		clock.advance(Duration.ofSeconds(1));
		assertTrue(limiter.checkUser(scope, user, Map.of(), 1).allowed());
		assertFalse(limiter.checkUser(scope, user, Map.of(), 1).allowed());
	}

	@Test
	void testUsersAreIsolated() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(1, 0, 0);

		Id first = randomUser();
		Id second = randomUser();

		assertTrue(limiter.checkUser(scope, first, Map.of(), 1).allowed());
		assertFalse(limiter.checkUser(scope, first, Map.of(), 1).allowed());
		// A second user has their own budget and is unaffected by the first exhausting theirs.
		assertTrue(limiter.checkUser(scope, second, Map.of(), 1).allowed());
	}

	/**
	 * The reason scopes exist: a Director hosts several APIs behind one server, and the same caller
	 * appearing in two of them must not spend one budget on both.
	 */
	@Test
	@DisplayName("Scopes: the same caller has an independent budget in each scope")
	void testScopesAreIsolatedForTheSameUser() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope client = new RateLimiter.Scope("client", RateLimitPolicy.perMinute(1));
		RateLimiter.Scope admin = new RateLimiter.Scope("admin", RateLimitPolicy.perMinute(1));
		Id user = randomUser();

		assertTrue(limiter.checkUser(client, user, null, 1).allowed());
		assertFalse(limiter.checkUser(client, user, null, 1).allowed());

		// Exhausting the client budget must not lock the same person out of the admin console.
		assertTrue(limiter.checkUser(admin, user, null, 1).allowed());
		assertFalse(limiter.checkUser(admin, user, null, 1).allowed());
	}

	/** An expensive request is worth more than one token, and must be charged as such. */
	@Test
	void testCostIsChargedInFull() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(10, 0, 0);
		Id user = randomUser();

		assertEquals(6, limiter.checkUser(scope, user, Map.of(), 4).remainingTokens());
		assertEquals(2, limiter.checkUser(scope, user, Map.of(), 4).remainingTokens());
		// Only 2 left, so a 4-token request is refused even though single-token ones would pass.
		assertFalse(limiter.checkUser(scope, user, Map.of(), 4).allowed());
		assertTrue(limiter.checkUser(scope, user, Map.of(), 2).allowed());
	}

	@Test
	void testAllWindowsAreEnforcedTogether() {
		RateLimiter limiter = limiter();
		// A generous burst allowance over a tight hourly budget: the hour is what actually governs.
		RateLimiter.Scope scope = scope(100, 5, 0);
		Id user = randomUser();

		for (int i = 0; i < 5; i++)
			assertTrue(limiter.checkUser(scope, user, Map.of(), 1).allowed());

		assertFalse(limiter.checkUser(scope, user, Map.of(), 1).allowed());
	}

	@Test
	void testNoLimitConfiguredMeansUnlimited() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = new RateLimiter.Scope("user", RateLimitPolicy.UNLIMITED);
		assertFalse(scope.defaults().isLimited());

		Id user = randomUser();
		for (int i = 0; i < 1000; i++)
			assertTrue(limiter.checkUser(scope, user, Map.of(), 4).allowed());
	}

	/**
	 * A caller with no resolvable identity cannot be charged, and must not be collapsed into a
	 * single shared bucket with every other such caller.
	 */
	@Test
	void testNullUserIsUnlimited() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(1, 0, 0);

		for (int i = 0; i < 100; i++)
			assertTrue(limiter.checkUser(scope, null, Map.of(), 1).allowed());
	}

	// -------------------------------------------------------------------------
	// Plan overrides from the authorizer
	// -------------------------------------------------------------------------

	@Test
	void testPlanOverrideRaisesTheLimit() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		Map<String, Object> plan = features("perMinute", 5);
		for (int i = 0; i < 5; i++)
			assertTrue(limiter.checkUser(scope, user, plan, 1).allowed());

		assertFalse(limiter.checkUser(scope, user, plan, 1).allowed());
	}

	@Test
	void testUpgradeTakesEffectImmediately() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		// Half the budget spent.
		assertEquals(1, limiter.checkUser(scope, user, Map.of(), 1).remainingTokens());

		// The user changes plan. The bucket is reconfigured on the next request rather than staying
		// pinned to the limits in force when it was created, so the new ceiling applies at once.
		// Tokens carry over proportionally - half spent stays half spent - so the upgrade grants
		// headroom without handing out a free full bucket.
		Map<String, Object> upgraded = features("perMinute", 10);
		assertEquals(4, limiter.checkUser(scope, user, upgraded, 1).remainingTokens());
		for (int i = 0; i < 4; i++)
			assertTrue(limiter.checkUser(scope, user, upgraded, 1).allowed());
		assertFalse(limiter.checkUser(scope, user, upgraded, 1).allowed());
	}

	/**
	 * The other direction of the same rule: churning between plans must not be a way to mint
	 * tokens, so an exhausted bucket stays exhausted across a change.
	 */
	@Test
	void testPlanChangeDoesNotRefillAnExhaustedBucket() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		assertTrue(limiter.checkUser(scope, user, Map.of(), 2).allowed());
		assertFalse(limiter.checkUser(scope, user, Map.of(), 1).allowed());

		assertFalse(limiter.checkUser(scope, user, features("perMinute", 100), 1).allowed());
	}

	@Test
	void testMalformedOverrideFallsBackToTheDefault() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		// Bad plan data must never read as "unlimited" - it falls back to the service default.
		Map<String, Object> broken = features("perMinute", "not-a-number");
		assertTrue(limiter.checkUser(scope, user, broken, 1).allowed());
		assertTrue(limiter.checkUser(scope, user, broken, 1).allowed());
		assertFalse(limiter.checkUser(scope, user, broken, 1).allowed());
	}

	@Test
	void testNegativeOverrideFallsBackToTheDefault() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		Map<String, Object> negative = features("perMinute", -1);
		assertTrue(limiter.checkUser(scope, user, negative, 1).allowed());
		assertTrue(limiter.checkUser(scope, user, negative, 1).allowed());
		assertFalse(limiter.checkUser(scope, user, negative, 1).allowed());
	}

	/** The flat spelling used before the plan features were aligned with the config is not read. */
	@Test
	void testLegacyFlatKeysAreIgnored() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		Map<String, Object> flat = Map.of("rateLimitPerMinute", 20);
		assertTrue(limiter.checkUser(scope, user, flat, 1).allowed());
		assertTrue(limiter.checkUser(scope, user, flat, 1).allowed());
		assertFalse(limiter.checkUser(scope, user, flat, 1).allowed());
	}

	@Test
	void testRateLimitOfUnexpectedTypeIsIgnored() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		Map<String, Object> broken = Map.of("rateLimit", "16/min");
		assertTrue(limiter.checkUser(scope, user, broken, 1).allowed());
		assertTrue(limiter.checkUser(scope, user, broken, 1).allowed());
		assertFalse(limiter.checkUser(scope, user, broken, 1).allowed());
	}

	/**
	 * Passing {@code null} features is how a caller declares a scope deliberately not plan-driven -
	 * the admin console, or a session that has not yet bound a user identity.
	 */
	@Test
	void testNullFeaturesUseTheScopeDefault() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(2, 0, 0);
		Id user = randomUser();

		assertTrue(limiter.checkUser(scope, user, null, 1).allowed());
		assertTrue(limiter.checkUser(scope, user, null, 1).allowed());
		assertFalse(limiter.checkUser(scope, user, null, 1).allowed());
	}

	// -------------------------------------------------------------------------
	// Per-address limits
	// -------------------------------------------------------------------------

	@Test
	void testAddressLimitIsEnforcedPerAddress() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = addressScope(2);

		assertTrue(limiter.checkAddress(scope, "10.0.0.1", 1).allowed());
		assertTrue(limiter.checkAddress(scope, "10.0.0.1", 1).allowed());
		assertFalse(limiter.checkAddress(scope, "10.0.0.1", 1).allowed());

		// A different address has its own budget.
		assertTrue(limiter.checkAddress(scope, "10.0.0.2", 1).allowed());
	}

	/**
	 * Permissionless routes are only ever seen by the address bucket, so an expensive request has to
	 * cost more here or it is not priced at all.
	 */
	@Test
	void testAddressLimitChargesTheRequestCost() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = addressScope(10);

		assertEquals(6, limiter.checkAddress(scope, "10.0.0.1", 4).remainingTokens());
		assertEquals(2, limiter.checkAddress(scope, "10.0.0.1", 4).remainingTokens());
		assertFalse(limiter.checkAddress(scope, "10.0.0.1", 4).allowed());
	}

	@Test
	void testAddressLimitDisabledAndNullAddressAreUnlimited() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope disabled = addressScope(0);
		for (int i = 0; i < 100; i++)
			assertTrue(limiter.checkAddress(disabled, "10.0.0.1", 4).allowed());

		// An address the server could not resolve must not collapse every such caller into one
		// shared bucket; it is waved through instead.
		RateLimiter.Scope enabled = addressScope(1);
		for (int i = 0; i < 100; i++)
			assertTrue(limiter.checkAddress(enabled, null, 1).allowed());
	}

	/**
	 * Address scopes are namespaced like user scopes, which is what lets a service give one route -
	 * an OAuth callback, say - a separate and far more generous allowance without that route's
	 * traffic draining the budget every other route shares.
	 */
	@Test
	@DisplayName("Scopes: one address has independent budgets in different address scopes")
	void testAddressScopesAreIsolated() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope general = new RateLimiter.Scope("address", RateLimitPolicy.perMinute(1));
		RateLimiter.Scope callback = new RateLimiter.Scope("address:callback", RateLimitPolicy.perMinute(1));

		assertTrue(limiter.checkAddress(general, "10.0.0.1", 1).allowed());
		assertFalse(limiter.checkAddress(general, "10.0.0.1", 1).allowed());

		assertTrue(limiter.checkAddress(callback, "10.0.0.1", 1).allowed());
	}

	// -------------------------------------------------------------------------
	// IPv6 grouping
	// -------------------------------------------------------------------------

	/**
	 * A single IPv6 host is routinely delegated an entire /64. Bucketing on the full address would
	 * let it mint a fresh budget per request by picking a new source address, which makes the
	 * per-address limit decorative.
	 */
	@Test
	@DisplayName("IPv6: addresses in one prefix share a bucket")
	void testIpv6AddressesInTheSamePrefixShareABucket() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = addressScope(2);

		assertTrue(limiter.checkAddress(scope, "2001:db8:1:2::1", 1).allowed());
		assertTrue(limiter.checkAddress(scope, "2001:db8:1:2::2", 1).allowed());
		// Third distinct address, same /64: no fresh budget.
		assertFalse(limiter.checkAddress(scope, "2001:db8:1:2::dead", 1).allowed());

		// A genuinely different /64 is a different client and gets its own budget.
		assertTrue(limiter.checkAddress(scope, "2001:db8:1:3::1", 1).allowed());
	}

	@Test
	@DisplayName("IPv6: the prefix width is configurable")
	void testIpv6PrefixWidthIsConfigurable() {
		// At /32 the two /64s above collapse into one bucket.
		RateLimiter limiter = RateLimiter.builder().clock(new TestClock()).ipv6PrefixBits(32).build();
		RateLimiter.Scope scope = addressScope(2);

		assertTrue(limiter.checkAddress(scope, "2001:db8:1:2::1", 1).allowed());
		assertTrue(limiter.checkAddress(scope, "2001:db8:9:9::1", 1).allowed());
		assertFalse(limiter.checkAddress(scope, "2001:db8:ff:ff::1", 1).allowed());
	}

	@Test
	@DisplayName("IPv6: IPv4 addresses are bucketed whole")
	void testIpv4AddressesAreNotGrouped() {
		RateLimiter limiter = limiter();

		assertEquals("v4:10.0.0.1", limiter.addressKey("10.0.0.1"));
		assertNotEquals(limiter.addressKey("10.0.0.1"), limiter.addressKey("10.0.0.2"));
		assertEquals(limiter.addressKey("2001:db8:1:2::1"), limiter.addressKey("2001:db8:1:2::2"));
		assertNull(limiter.addressKey(null));
		assertNull(limiter.addressKey("  "));

		// A trusted proxy header carrying something unparseable keeps its own bucket rather than
		// dropping the limit for that request entirely.
		assertEquals("raw:not-an-address", limiter.addressKey("not-an-address"));
	}

	// -------------------------------------------------------------------------
	// Service-wide limit
	// -------------------------------------------------------------------------

	@Test
	void testServiceLimitIsSharedAcrossCallers() {
		TestClock clock = new TestClock();
		RateLimiter limiter = RateLimiter.builder().clock(clock).servicePerSecond(3).build();
		assertTrue(limiter.isServiceLimited());

		assertTrue(limiter.checkService(1).allowed());
		assertTrue(limiter.checkService(1).allowed());
		assertTrue(limiter.checkService(1).allowed());
		assertFalse(limiter.checkService(1).allowed());

		clock.advance(Duration.ofSeconds(1));
		assertTrue(limiter.checkService(3).allowed());
	}

	@Test
	void testServiceLimitDisabledIsUnlimited() {
		RateLimiter limiter = limiter();
		assertFalse(limiter.isServiceLimited());
		for (int i = 0; i < 1000; i++)
			assertTrue(limiter.checkService(4).allowed());
	}

	// -------------------------------------------------------------------------
	// Refunds
	// -------------------------------------------------------------------------

	@Test
	void testRefundReturnsServiceTokens() {
		RateLimiter limiter = RateLimiter.builder().clock(new TestClock()).servicePerSecond(4).build();

		assertTrue(limiter.checkService(4).allowed());
		assertFalse(limiter.checkService(1).allowed());

		// The request was refused for the node's own reason, so its tokens come back.
		limiter.refundService(4);
		assertTrue(limiter.checkService(4).allowed());
	}

	@Test
	void testRefundNeverExceedsCapacity() {
		RateLimiter limiter = RateLimiter.builder().clock(new TestClock()).servicePerSecond(4).build();

		// A refund with no matching charge must not mint tokens beyond the configured ceiling.
		limiter.refundService(1000);
		assertTrue(limiter.checkService(4).allowed());
		assertFalse(limiter.checkService(1).allowed());
	}

	@Test
	void testRefundReturnsUserTokens() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = scope(4, 0, 0);
		Id user = randomUser();

		assertEquals(0, limiter.checkUser(scope, user, Map.of(), 4).remainingTokens());
		assertFalse(limiter.checkUser(scope, user, Map.of(), 1).allowed());

		limiter.refundUser(scope, user, Map.of(), 4);
		assertEquals(0, limiter.checkUser(scope, user, Map.of(), 4).remainingTokens());
	}

	@Test
	void testRefundReturnsAddressTokens() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope scope = addressScope(4);

		assertEquals(0, limiter.checkAddress(scope, "10.0.0.1", 4).remainingTokens());
		assertFalse(limiter.checkAddress(scope, "10.0.0.1", 1).allowed());

		limiter.refundAddress(scope, "10.0.0.1", 4);
		assertEquals(0, limiter.checkAddress(scope, "10.0.0.1", 4).remainingTokens());
	}

	@Test
	void testRefundIsHarmlessWhenLimitsAreDisabled() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope unlimited = new RateLimiter.Scope("user", RateLimitPolicy.UNLIMITED);

		// Nothing was charged, so nothing is given back - and none of these may throw.
		limiter.refundService(4);
		limiter.refundUser(unlimited, randomUser(), Map.of(), 4);
		limiter.refundAddress(addressScope(0), "10.0.0.1", 4);
		limiter.refundUser(scope(4, 0, 0), null, Map.of(), 4);
		limiter.refundAddress(addressScope(4), null, 4);
	}

	// -------------------------------------------------------------------------
	// Lifecycle
	// -------------------------------------------------------------------------

	@Test
	void testCloseReleasesBuckets() {
		RateLimiter limiter = limiter();
		RateLimiter.Scope user = scope(1, 0, 0);
		RateLimiter.Scope address = addressScope(1);
		Id id = randomUser();

		assertTrue(limiter.checkUser(user, id, Map.of(), 1).allowed());
		assertFalse(limiter.checkUser(user, id, Map.of(), 1).allowed());
		assertTrue(limiter.checkAddress(address, "10.0.0.1", 1).allowed());
		assertFalse(limiter.checkAddress(address, "10.0.0.1", 1).allowed());

		limiter.close();

		// A redeploy within the same JVM starts from a clean slate rather than inheriting the
		// previous generation of buckets.
		assertTrue(limiter.checkUser(user, id, Map.of(), 1).allowed());
		assertTrue(limiter.checkAddress(address, "10.0.0.1", 1).allowed());
	}

	// -------------------------------------------------------------------------
	// Builder validation
	// -------------------------------------------------------------------------

	@Test
	void testBuilderRejectsNonsenseValues() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> RateLimiter.builder().servicePerSecond(-1));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> RateLimiter.builder().maxTrackedClients(0));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> RateLimiter.builder().ipv6PrefixBits(129));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new RateLimitPolicy(-1, 0, 0));
	}
}
