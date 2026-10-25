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


package io.bosonnetwork.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.cwt.Claim;
import io.bosonnetwork.cwt.InvalidClaimException;
import io.bosonnetwork.cwt.InvalidSignatureException;
import io.bosonnetwork.cwt.ReplayCache;
import io.bosonnetwork.cwt.SignedCwt;
import io.bosonnetwork.cwt.TokenExpiredException;
import io.bosonnetwork.cwt.TokenReplayException;
import io.bosonnetwork.service.FederationStatement.Purpose;

class FederationStatementTests {
	private final Identity alice = new CryptoIdentity();
	private final Identity bob = new CryptoIdentity();

	private byte[] proposal() {
		return FederationStatement.builder(alice, Purpose.PROPOSE).audience(bob.getId()).build();
	}

	// A CWT signed by alice the way the builder would, with every claim under the test's control.
	private SignedCwt.Builder handMade(Purpose purpose, long lifetimeSeconds) {
		long now = System.currentTimeMillis() / 1000;
		return SignedCwt.builder(alice)
				.subject(alice.getId())
				.issuedAt(new Date(now * 1000))
				.expiration(new Date((now + lifetimeSeconds) * 1000))
				.claim(Claim.PURPOSE.getValue(), purpose.label());
	}

	@Test
	void labelsFollowTheFormatsRegistry() {
		assertEquals("boson:sig:1:federation-propose", Purpose.PROPOSE.label());
		assertEquals("boson:sig:1:federation-accept", Purpose.ACCEPT.label());
		assertEquals("boson:sig:1:federation-decline", Purpose.DECLINE.label());
		assertEquals("boson:sig:1:federation-revoke", Purpose.REVOKE.label());
		assertEquals("boson:sig:1:node-descriptor", Purpose.NODE_DESCRIPTOR.label());
	}

	@Test
	void anAddressedStatementVerifiesForItsAudience() throws Exception {
		byte[] content = Random.randomBytes(8);
		byte[] token = FederationStatement.builder(alice, Purpose.PROPOSE)
				.audience(bob.getId())
				.lifetime(Duration.ofMinutes(2))
				.content(Map.of("note", "hello", "data", content, "list", List.of(1, 2), "map", Map.of("k", true)))
				.build();

		FederationStatement s = FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(token);
		assertEquals(Purpose.PROPOSE, s.getPurpose());
		assertEquals(alice.getId(), s.getIssuer());
		assertEquals(bob.getId(), s.getAudience());
		assertEquals(Duration.ofMinutes(2), Duration.between(s.getIssuedAt(), s.getExpiresAt()));
		assertNotNull(s.getTokenId());
		assertEquals(FederationStatement.TOKEN_ID_BYTES, s.getTokenId().length);
		assertEquals("hello", s.getContent().get("note"));
		assertArrayEquals(content, (byte[]) s.getContent().get("data"));
		assertEquals(List.of(1, 2), s.getContent().get("list"));
		assertEquals(Map.of("k", true), s.getContent().get("map"));
	}

	@Test
	void eachAddressedStatementHasItsOwnId() throws Exception {
		FederationStatement.Verifier v = FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId());
		byte[] id1 = v.verify(proposal()).getTokenId();
		byte[] id2 = v.verify(proposal()).getTokenId();
		assertNotNull(id1);
		assertNotNull(id2);
		assertFalse(Arrays.equals(id1, id2));
	}

	@Test
	void aStatementForAnotherNodeIsRefused() {
		byte[] token = proposal();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(Id.random()).verify(token));
	}

	@Test
	void anAddressedPurposeCannotBeVerifiedWithoutKnowingWhoWeAre() {
		byte[] token = proposal();
		assertThrows(IllegalStateException.class, () -> FederationStatement.verifier(Purpose.PROPOSE).verify(token));
	}

	@Test
	void aStatementOfOnePurposeIsNotAnother() {
		byte[] token = proposal();
		InvalidClaimException e = assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.ACCEPT).audience(bob.getId()).verify(token));
		assertTrue(e.getMessage().contains("Not a ACCEPT statement"), e.getMessage());
	}

	@Test
	void anAccessTokenIsNotAStatement() {
		byte[] token = SignedCwt.builder(alice)
				.subject(alice.getId())
				.audience(bob.getId())
				.issuedAtNow()
				.expiration(Duration.ofMinutes(1))
				.tokenId(Random.randomBytes(16))
				.build();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(token));
	}

	@Test
	void theExpectedIssuerIsEnforced() {
		byte[] token = proposal();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).issuer(Id.random()).audience(bob.getId()).verify(token));
	}

	@Test
	void aStatementIsAboutItsIssuer() {
		byte[] token = handMade(Purpose.PROPOSE, 60)
				.subject(Id.random())
				.audience(bob.getId())
				.tokenId(Random.randomBytes(16))
				.build();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(token));
	}

	@Test
	void aStatementMayNotOutliveItsPurpose() {
		byte[] token = handMade(Purpose.PROPOSE, Purpose.PROPOSE.maxLifetime().toSeconds() + 1)
				.audience(bob.getId())
				.tokenId(Random.randomBytes(16))
				.build();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(token));

		assertThrows(IllegalArgumentException.class, () ->
				FederationStatement.builder(alice, Purpose.PROPOSE).lifetime(Duration.ofMinutes(6)));
	}

	@Test
	void aStatementWithoutDatesIsRefused() {
		byte[] token = SignedCwt.builder(alice)
				.subject(alice.getId())
				.audience(bob.getId())
				.tokenId(Random.randomBytes(16))
				.claim(Claim.PURPOSE.getValue(), Purpose.PROPOSE.label())
				.build();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(token));
	}

	@Test
	void anAddressedStatementNeedsItsId() {
		byte[] noId = handMade(Purpose.PROPOSE, 60).audience(bob.getId()).build();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(noId));

		byte[] shortId = handMade(Purpose.PROPOSE, 60).audience(bob.getId()).tokenId(Random.randomBytes(8)).build();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(shortId));
	}

	@Test
	void anAddressedStatementIsAcceptedOnceWithACache() throws Exception {
		ReplayCache cache = new ReplayCache();
		FederationStatement.Verifier v = FederationStatement.verifier(Purpose.PROPOSE)
				.audience(bob.getId())
				.replayCache(cache);
		byte[] token = proposal();

		v.verify(token);
		assertThrows(TokenReplayException.class, () -> v.verify(token));
		// A new statement is a new id.
		v.verify(proposal());
	}

	@Test
	void anAddressedPurposeNeedsAnAudienceAndOthersTakeNone() {
		assertThrows(IllegalStateException.class, () -> FederationStatement.builder(alice, Purpose.PROPOSE).build());
		assertThrows(IllegalStateException.class, () ->
				FederationStatement.builder(alice, Purpose.NODE_DESCRIPTOR).audience(bob.getId()).build());

		byte[] addressedDescriptor = handMade(Purpose.NODE_DESCRIPTOR, 60).audience(bob.getId()).build();
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.verifier(Purpose.NODE_DESCRIPTOR).verify(addressedDescriptor));
	}

	@Test
	void aStatementForAnyoneHasNoIdOrAudience() throws Exception {
		byte[] token = FederationStatement.builder(alice, Purpose.NODE_DESCRIPTOR).build();
		FederationStatement s = FederationStatement.verifier(Purpose.NODE_DESCRIPTOR).verify(token);
		assertNull(s.getAudience());
		assertNull(s.getTokenId());
		assertEquals(Map.of(), s.getContent());
		assertEquals(Purpose.NODE_DESCRIPTOR.maxLifetime(), Duration.between(s.getIssuedAt(), s.getExpiresAt()));
	}

	@Test
	void anExpiredStatementIsRefused() {
		byte[] token = handMade(Purpose.PROPOSE, -600)
				.audience(bob.getId())
				.tokenId(Random.randomBytes(16))
				.build();
		assertThrows(TokenExpiredException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).leeway(0).verify(token));
	}

	@Test
	void aTamperedStatementIsRefused() {
		byte[] token = proposal();
		token[token.length - 1] ^= 1;
		assertThrows(InvalidSignatureException.class, () ->
				FederationStatement.verifier(Purpose.PROPOSE).audience(bob.getId()).verify(token));
	}

	@Test
	void contentMustBeAMap() {
		byte[] notAMap = handMade(Purpose.NODE_DESCRIPTOR, 60).claim(Claim.CONTENT.getValue(), List.of("x")).build();
		assertThrows(InvalidClaimException.class, () -> FederationStatement.verifier(Purpose.NODE_DESCRIPTOR).verify(notAMap));
	}
}
