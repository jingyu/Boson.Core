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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.cwt.InvalidClaimException;
import io.bosonnetwork.cwt.InvalidSignatureException;
import io.bosonnetwork.service.FederationStatement.Purpose;

class NodeDescriptorTests {
	private static final String MESSAGING = "io.bosonnetwork.photonmessaging";
	private static final String ION_STORE = "io.bosonnetwork.ionstore";

	private final Identity node = new CryptoIdentity();
	private final Id messagingPeer = Id.random();
	private final Id ionStorePeer = Id.random();

	private NodeDescriptor full() {
		return NodeDescriptor.builder()
				.nodeId(node.getId())
				.software("Boson Super Node", "3.2.1")
				.name("Whisper").logo("https://whisper.example/logo.png").website("https://whisper.example")
				.contact("admin@whisper.example")
				.mode(FederationMode.MUTUAL)
				.protocol("federation/2")
				.capability("descriptor")
				.service(new NodeDescriptor.Service(MESSAGING, "Photon Messaging", messagingPeer,
						"mqtts://whisper.example:8883", true,
						Map.of("federationEndpoint", "https://whisper.example:9084")))
				.service(new NodeDescriptor.Service(ION_STORE, null, ionStorePeer, "https://whisper.example:9002",
						false, Map.of()))
				.build();
	}

	@Test
	void aSignedDescriptorVerifiesWithEverythingItSays() throws Exception {
		byte[] s = FederationStatement.nodeDescriptor(node, full());
		NodeDescriptor d = FederationStatement.asDescriptor(s, node.getId());

		assertEquals(node.getId(), d.getNodeId());
		assertEquals(Optional.of("Boson Super Node"), d.getSoftware());
		assertEquals(Optional.of("3.2.1"), d.getVersion());
		assertEquals(Optional.of("Whisper"), d.getName());
		assertEquals(Optional.of("https://whisper.example/logo.png"), d.getLogo());
		assertEquals(Optional.of("https://whisper.example"), d.getWebsite());
		assertEquals(Optional.of("admin@whisper.example"), d.getContact());
		assertEquals(Optional.of(FederationMode.MUTUAL), d.getMode());
		assertEquals(List.of("federation/2"), d.getProtocols());
		assertEquals(Set.of("descriptor"), d.getCapabilities());
		assertEquals(Purpose.NODE_DESCRIPTOR.maxLifetime(), Duration.between(d.getIssuedAt(), d.getExpiresAt()));

		assertEquals(2, d.getServices().size());
		NodeDescriptor.Service messaging = d.getService(messagingPeer).orElseThrow();
		assertEquals(MESSAGING, messaging.type());
		assertEquals("Photon Messaging", messaging.name());
		assertEquals("mqtts://whisper.example:8883", messaging.endpoint());
		assertTrue(messaging.federation());
		assertEquals(Map.of("federationEndpoint", "https://whisper.example:9084"), messaging.extra());

		NodeDescriptor.Service ionStore = d.getServices(ION_STORE).get(0);
		assertEquals(ionStorePeer, ionStore.peerId());
		assertEquals(null, ionStore.name());
		assertFalse(ionStore.federation());

		assertEquals(List.of(), d.getServices("io.bosonnetwork.turn"));
		assertTrue(d.getService(Id.random()).isEmpty());
	}

	@Test
	void aMinimalDescriptorListsNoServicesAndIsOpen() throws Exception {
		byte[] s = FederationStatement.nodeDescriptor(node,
				NodeDescriptor.builder().nodeId(node.getId()).build());
		NodeDescriptor d = FederationStatement.asDescriptor(s, node.getId());
		assertEquals(List.of(), d.getServices());
		assertEquals(Optional.of(FederationMode.OPEN), d.getMode());
		assertTrue(d.getName().isEmpty());
		assertTrue(d.getSoftware().isEmpty());
	}

	@Test
	void aDescriptorFromAnotherNodeIsRefused() {
		byte[] encoded = FederationStatement.nodeDescriptor(node, full());
		assertThrows(InvalidClaimException.class, () -> FederationStatement.asDescriptor(encoded, Id.random()));
	}

	@Test
	void aTamperedDescriptorIsRefused() {
		byte[] encoded = FederationStatement.nodeDescriptor(node, full());
		encoded[encoded.length - 1] ^= 1;
		assertThrows(InvalidSignatureException.class, () -> FederationStatement.asDescriptor(encoded, node.getId()));
	}

	@Test
	void anotherStatementIsNotADescriptor() {
		byte[] proposal = FederationStatement.propose(node, Id.random(), null);
		assertThrows(InvalidClaimException.class, () -> FederationStatement.asDescriptor(proposal, node.getId()));
	}

	private byte[] withContent(Map<String, Object> content) {
		return FederationStatement.builder(node, Purpose.NODE_DESCRIPTOR).content(content).build();
	}

	@Test
	void aDescriptorMustListItsServicesWellFormed() {
		assertThrows(InvalidClaimException.class, () -> FederationStatement.asDescriptor(withContent(Map.of()), node.getId()));
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.asDescriptor(withContent(Map.of("services", "none")), node.getId()));

		Map<String, Object> noType = Map.of("peerId", Id.random().getBytes(), "endpoint", "https://x");
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.asDescriptor(withContent(Map.of("services", List.of(noType))), node.getId()));

		Map<String, Object> shortPeer = Map.of("type", MESSAGING, "peerId", new byte[8], "endpoint", "https://x");
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.asDescriptor(withContent(Map.of("services", List.of(shortPeer))), node.getId()));

		Map<String, Object> noEndpoint = Map.of("type", MESSAGING, "peerId", Id.random().getBytes());
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.asDescriptor(withContent(Map.of("services", List.of(noEndpoint))), node.getId()));

		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.asDescriptor(withContent(Map.of("services", List.of(), "name", 7)), node.getId()));
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.asDescriptor(withContent(Map.of("services", List.of(), "protocols", List.of(1))), node.getId()));
	}

	@Test
	void unknownKeysAndModesAreIgnored() throws Exception {
		Map<String, Object> service = Map.of("type", MESSAGING, "peerId", messagingPeer.getBytes(),
				"endpoint", "https://x", "future", "field");
		NodeDescriptor d = FederationStatement.asDescriptor(withContent(Map.of("services", List.of(service),
				"mode", "federated-by-carrier-pigeon", "future", 42)), node.getId());

		// A newer version's mode: as if the node said none, not a malformed descriptor.
		assertTrue(d.getMode().isEmpty());
		assertEquals(messagingPeer, d.getServices().get(0).peerId());
		// A service that doesn't say whether other nodes may use it is not offered to them.
		assertFalse(d.getServices().get(0).federation());
	}

	@Test
	void aModeIsReadByItsWireNameAndOneNotTextIsIgnored() throws Exception {
		assertEquals(Optional.of(FederationMode.ALLOWLIST), FederationStatement.asDescriptor(
				withContent(Map.of("services", List.of(), "mode", "allowlist")), node.getId()).getMode());
		assertTrue(FederationStatement.asDescriptor(
				withContent(Map.of("services", List.of(), "mode", 2)), node.getId()).getMode().isEmpty());
		assertTrue(FederationStatement.asDescriptor(
				withContent(Map.of("services", List.of())), node.getId()).getMode().isEmpty());
	}

	@Test
	void aBuiltDescriptorIsDatedByItsOwnLifetimeAndSigningByTheStatements() throws Exception {
		Instant before = Instant.now();
		NodeDescriptor forever = NodeDescriptor.builder().nodeId(node.getId()).build();
		assertFalse(forever.getIssuedAt().isBefore(before.truncatedTo(ChronoUnit.MILLIS)));
		assertTrue(forever.getExpiresAt().isAfter(Instant.parse("9999-12-31T23:59:59Z")));

		NodeDescriptor hour = NodeDescriptor.builder().nodeId(node.getId()).lifetime(Duration.ofHours(1)).build();
		assertEquals(Duration.ofHours(1), Duration.between(hour.getIssuedAt(), hour.getExpiresAt()));

		// The statement's lifetime is the signer's, not the builder's.
		NodeDescriptor signed = FederationStatement.asDescriptor(
				FederationStatement.nodeDescriptor(node, hour, Duration.ofMinutes(10)), node.getId());
		assertEquals(Duration.ofMinutes(10), Duration.between(signed.getIssuedAt(), signed.getExpiresAt()));
	}

	@Test
	void aNodeSignsOnlyItsOwnDescriptor() {
		NodeDescriptor other = NodeDescriptor.builder().nodeId(Id.random()).build();
		assertThrows(IllegalArgumentException.class, () -> FederationStatement.nodeDescriptor(node, other));
		assertThrows(IllegalStateException.class, () -> NodeDescriptor.builder().build());

		NodeDescriptor own = NodeDescriptor.builder().nodeId(node.getId()).build();
		assertThrows(IllegalArgumentException.class, () ->
				FederationStatement.nodeDescriptor(node, own, Duration.ofDays(2)));
		assertThrows(IllegalArgumentException.class, () ->
				FederationStatement.nodeDescriptor(node, own, Duration.ZERO));
	}

	@Test
	void aDescriptorLivesADayAtMost() {
		assertThrows(IllegalArgumentException.class, () -> NodeDescriptor.builder().lifetime(Duration.ofDays(2)));
		assertThrows(IllegalArgumentException.class, () -> NodeDescriptor.builder().lifetime(Duration.ZERO));
	}
}
