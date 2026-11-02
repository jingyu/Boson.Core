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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.CryptoIdentity;

class FederatedServiceTests {
	private static PeerInfo announcement() {
		return PeerInfo.builder().identity(new CryptoIdentity()).node(new CryptoIdentity())
				.endpoint("https://service.example").sequenceNumber(2)
				.extra(Map.of("key", "value")).build();
	}

	@Test
	void aValidAttestedAnnouncementBecomesAService() {
		PeerInfo peer = announcement();
		FederatedService service = FederatedService.of(peer, "io.bosonnetwork.test", "Test");
		assertEquals(peer.getId(), service.peerId());
		assertEquals(peer.getNodeId(), service.nodeId());
		assertEquals(peer.getEndpoint(), service.endpoint());
		assertEquals(peer.getFingerprint(), service.fingerprint());
		assertEquals(peer.getSequenceNumber(), service.sequenceNumber());
		assertEquals(Map.of("key", "value"), service.extra());
		assertEquals("io.bosonnetwork.test", service.serviceType());
		assertEquals("Test", service.serviceName());
	}

	@ParameterizedTest
	@ValueSource(strings = {"peer-signature", "node-signature", "endpoint"})
	void anAttestationPresentButInvalidAnnouncementIsRejected(String corruption) {
		PeerInfo peer = announcement();
		byte[] signature = peer.getSignature().clone();
		byte[] nodeSignature = Objects.requireNonNull(peer.getNodeSignature()).clone();
		String endpoint = peer.getEndpoint();
		switch (corruption) {
			case "peer-signature" -> signature[0] ^= 1;
			case "node-signature" -> nodeSignature[0] ^= 1;
			case "endpoint" -> endpoint = "https://changed.example";
			default -> throw new AssertionError(corruption);
		}
		PeerInfo invalid = PeerInfo.of(peer.getId(), peer.getSequenceNumber(), peer.getNodeId(),
				nodeSignature, signature, peer.getFingerprint(), endpoint, peer.getExtraData());

		assertTrue(invalid.isAuthenticated());
		assertFalse(invalid.isValid());
		assertThrows(IllegalArgumentException.class, () -> FederatedService.of(invalid, null, null));
		assertTrue(FederatedService.fromAnnouncements(List.of(invalid)).isEmpty());
		assertEquals(peer.getEndpoint(), FederatedService.fromAnnouncements(List.of(invalid, peer))
				.orElseThrow().endpoint());
	}

	@Test
	void aValidUnattestedAnnouncementIsRejected() {
		PeerInfo peer = PeerInfo.builder().identity(new CryptoIdentity())
				.endpoint("https://service.example").build();
		assertTrue(peer.isValid());
		assertFalse(peer.isAuthenticated());
		assertThrows(IllegalArgumentException.class, () -> FederatedService.of(peer, null, null));
	}
}
