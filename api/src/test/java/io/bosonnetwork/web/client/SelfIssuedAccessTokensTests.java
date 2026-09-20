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

package io.bosonnetwork.web.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.cwt.Claim;
import io.bosonnetwork.cwt.SignedCwt;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.service.AccessScope;

/**
 * Tests of the tokens a client signs for itself: what they claim, when they are renewed, and how a
 * refusal that reveals the service's clock is turned into a correction and one retry.
 */
class SelfIssuedAccessTokensTests {
	private static final Identity user = new CryptoIdentity();
	private static final Identity device = new CryptoIdentity();
	private static final Id node = Id.random();

	private static SelfIssuedAccessTokens.Builder tokens() {
		return SelfIssuedAccessTokens.builder(device)
				.subject(user.getId())
				.clientId(device.getId())
				.scope(AccessScope.CLIENT)
				.audience(node);
	}

	private static SignedCwt parse(String token) throws Exception {
		return SignedCwt.parse(Json.BASE64_DECODER.decode(token), (int) Duration.ofDays(1).toSeconds());
	}

	private static String issued(SelfIssuedAccessTokens source) {
		return source.token().result();
	}

	// Time claims are carried as epoch seconds.
	private static long seconds(SignedCwt cwt, Claim claim) {
		Number value = cwt.getClaim(claim.getValue());
		return value.longValue();
	}

	private static long notBefore(SignedCwt cwt) {
		return seconds(cwt, Claim.NOT_BEFORE) * 1000;
	}

	@Test
	void aTokenClaimsTheConfiguredIdentityAndScope() throws Exception {
		SignedCwt cwt = parse(issued(tokens().build()));

		assertEquals(device.getId(), cwt.getClaimAsId(Claim.ISSUER.getValue()));
		assertEquals(user.getId(), cwt.getClaimAsId(Claim.SUBJECT.getValue()));
		assertEquals(node, cwt.getClaimAsId(Claim.AUDIENCE.getValue()));
		assertEquals(AccessScope.CLIENT.toString(), cwt.getClaimAsString(Claim.SCOPE.getValue()));
	}

	@Test
	void aTokenIsBackdatedAndExpiresAfterItsLifetime() throws Exception {
		long issuedAt = System.currentTimeMillis();
		SignedCwt cwt = parse(issued(tokens().lifetime(Duration.ofMinutes(30)).build()));

		// Backdated, so that a service whose clock is a little behind does not see a future token.
		long backdate = issuedAt - notBefore(cwt);
		assertTrue(backdate >= SelfIssuedAccessTokens.BACKDATE.toMillis() - 1000, "backdate: " + backdate);

		// Time claims are truncated to the second, so the lifetime is right to within one.
		long expiration = seconds(cwt, Claim.EXPIRATION) * 1000;
		long lifetime = expiration - issuedAt;
		assertTrue(Math.abs(lifetime - Duration.ofMinutes(30).toMillis()) < 2000, "lifetime: " + lifetime);
	}

	@Test
	void theSameTokenServesEveryRequestUntilItIsDue() {
		SelfIssuedAccessTokens source = tokens().build();
		assertEquals(issued(source), issued(source));
	}

	@Test
	void aRefusalWithoutADateDropsTheTokenButIsFinal() {
		SelfIssuedAccessTokens source = tokens().build();
		String token = issued(source);

		// Nothing says the clock is to blame, so the caller must not retry.
		assertFalse(source.rejected(token, null));
		assertEquals(0, source.clockOffset());
		// The refused token is dropped all the same, but that is not observable here: reissuing it in
		// the same second yields the same bytes, since the time claims are second-granular and the
		// signature is deterministic.
		assertNotNull(issued(source));
	}

	@Test
	void aServiceClockCloseToOursIsNotASkew() {
		SelfIssuedAccessTokens source = tokens().build();
		String token = issued(source);

		Instant almostNow = Instant.now().plusMillis(SelfIssuedAccessTokens.MAX_CLOCK_SKEW.toMillis() / 2);
		assertFalse(source.rejected(token, almostNow));
		assertEquals(0, source.clockOffset());
	}

	@Test
	void aSkewedServiceClockIsAdoptedAndTheRequestIsWorthARetry() throws Exception {
		SelfIssuedAccessTokens source = tokens().build();
		String token = issued(source);

		Duration ahead = Duration.ofMinutes(20);
		long now = System.currentTimeMillis();
		assertTrue(source.rejected(token, Instant.ofEpochMilli(now).plus(ahead)));
		assertTrue(Math.abs(source.clockOffset() - ahead.toMillis()) < 5000, "offset: " + source.clockOffset());

		// The next token is dated by the service's clock, so it is no longer in the service's future.
		long dated = notBefore(parse(issued(source))) - now;
		assertTrue(dated > ahead.toMillis() - SelfIssuedAccessTokens.BACKDATE.toMillis() - 5000, "dated: " + dated);
	}

	@Test
	void requestsThatCarriedTheTokenWhichRevealedTheSkewAreRetriedToo() {
		SelfIssuedAccessTokens source = tokens().build();
		String token = issued(source);
		Instant serviceNow = Instant.now().plus(Duration.ofMinutes(20));

		assertTrue(source.rejected(token, serviceNow));
		// Concurrent requests carried the same token and were refused for the same reason; they find
		// the clock already corrected, and deserve the same one retry.
		assertTrue(source.rejected(token, serviceNow));

		// A token issued after the correction, refused again, is not the clock's fault any more.
		assertFalse(source.rejected(issued(source), serviceNow));
	}

	@Test
	void whatEveryTokenNeedsIsRequired() {
		assertThrows(NullPointerException.class, () ->
				SelfIssuedAccessTokens.builder(device).scope(AccessScope.CLIENT).audience(node).build());
		assertThrows(NullPointerException.class, () ->
				SelfIssuedAccessTokens.builder(device).subject(user.getId()).audience(node).build());
		assertThrows(NullPointerException.class, () ->
				SelfIssuedAccessTokens.builder(device).subject(user.getId()).scope(AccessScope.CLIENT).build());
		assertThrows(IllegalArgumentException.class, () -> tokens().lifetime(Duration.ZERO));
	}
}
