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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.cwt.InvalidClaimException;
import io.bosonnetwork.cwt.InvalidSignatureException;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.service.FederationStatement.Purpose;

class FederatedNodeDescriptorTests {
	private static final String MESSAGING = "io.bosonnetwork.photonmessaging";
	private static final String ION_STORE = "io.bosonnetwork.ionstore";

	private final Identity node = new CryptoIdentity();
	private final Id messagingPeer = Id.random();
	private final Id ionStorePeer = Id.random();

	private FederatedNodeDescriptor full() {
		return FederatedNodeDescriptor.builder(node.getId())
				.software("Boson Super Node", "3.2.1")
				.name("Whisper").logo("https://whisper.example/logo.png").website("https://whisper.example")
				.contact("admin@whisper.example")
				.mode(FederationMode.MUTUAL)
				.protocol("federation/2")
				.capability("descriptor")
				.service(new FederatedNodeDescriptor.Service(MESSAGING, "Photon Messaging", messagingPeer,
						"mqtts://whisper.example:8883", true,
						Map.of("federationEndpoint", "https://whisper.example:9084")))
				.service(new FederatedNodeDescriptor.Service(ION_STORE, null, ionStorePeer, "https://whisper.example:9002",
						false, Map.of()))
				.build();
	}

	@Test
	void aSignedDescriptorVerifiesWithEverythingItSays() throws Exception {
		byte[] s = FederationStatement.nodeDescriptor(node, full());
		FederatedNodeDescriptor d = FederationStatement.asDescriptor(s, node.getId());

		assertEquals(node.getId(), d.getNodeId());
		assertEquals(Optional.of("Boson Super Node"), d.getSoftware());
		assertEquals(Optional.of("3.2.1"), d.getVersion());
		assertEquals(Optional.of("Whisper"), d.getName());
		assertEquals(Optional.of("https://whisper.example/logo.png"), d.getLogo());
		assertEquals(Optional.of("https://whisper.example"), d.getWebsite());
		assertEquals(Optional.of("admin@whisper.example"), d.getContact());
		assertEquals(Optional.of(FederationMode.MUTUAL), d.getMode());
		assertEquals(List.of("federation/2"), d.getProtocols());
		assertEquals(List.of("descriptor"), d.getCapabilities());
		assertEquals(Purpose.NODE_DESCRIPTOR.maxLifetime(), Duration.between(d.getIssuedAt(), d.getExpiresAt()));

		assertEquals(2, d.getServices().size());
		FederatedNodeDescriptor.Service messaging = d.getService(messagingPeer).orElseThrow();
		assertEquals(MESSAGING, messaging.getType());
		assertEquals(Optional.of("Photon Messaging"), messaging.getName());
		assertEquals("mqtts://whisper.example:8883", messaging.getEndpoint());
		assertTrue(messaging.isFederation());
		assertEquals(Map.of("federationEndpoint", "https://whisper.example:9084"), messaging.getExtra());

		FederatedNodeDescriptor.Service ionStore = d.getServices(ION_STORE).get(0);
		assertEquals(ionStorePeer, ionStore.getPeerId());
		assertTrue(ionStore.getName().isEmpty());
		assertFalse(ionStore.isFederation());

		assertEquals(List.of(), d.getServices("io.bosonnetwork.turn"));
		assertTrue(d.getService(Id.random()).isEmpty());
	}

	@Test
	void aMinimalDescriptorListsNoServicesAndIsOpen() throws Exception {
		byte[] s = FederationStatement.nodeDescriptor(node,
				FederatedNodeDescriptor.builder(node.getId()).build());
		FederatedNodeDescriptor d = FederationStatement.asDescriptor(s, node.getId());
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

		Map<String, Object> textFlag = Map.of("type", MESSAGING, "peerId", Id.random().getBytes(),
				"endpoint", "https://x", "federation", "true");
		assertThrows(InvalidClaimException.class, () ->
				FederationStatement.asDescriptor(withContent(Map.of("services", List.of(textFlag))), node.getId()));
	}

	@Test
	void unknownKeysAndModesAreIgnored() throws Exception {
		Map<String, Object> service = Map.of("type", MESSAGING, "peerId", messagingPeer.getBytes(),
				"endpoint", "https://x", "future", "field");
		FederatedNodeDescriptor d = FederationStatement.asDescriptor(withContent(Map.of("services", List.of(service),
				"mode", "federated-by-carrier-pigeon", "future", 42)), node.getId());

		// A newer version's mode: as if the node said none, not a malformed descriptor.
		assertTrue(d.getMode().isEmpty());
		assertEquals(messagingPeer, d.getServices().get(0).getPeerId());
		// A service that doesn't say whether other nodes may use it is not offered to them.
		assertFalse(d.getServices().get(0).isFederation());
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
	void aBuiltDescriptorIsNotSignedAndASignedOneIsDatedByItsStatement() throws Exception {
		FederatedNodeDescriptor built = full();
		assertFalse(built.isSigned());
		assertEquals(Instant.EPOCH, built.getIssuedAt());
		assertEquals(Instant.EPOCH, built.getExpiresAt());

		FederatedNodeDescriptor signed = FederationStatement.asDescriptor(
				FederationStatement.nodeDescriptor(node, built, Duration.ofMinutes(10)), node.getId());
		assertTrue(signed.isSigned());
		assertEquals(Duration.ofMinutes(10), Duration.between(signed.getIssuedAt(), signed.getExpiresAt()));
	}

	@Test
	void theContentCannotClaimItsOwnNodeOrDates() throws Exception {
		long forever = Instant.parse("9999-01-01T00:00:00Z").toEpochMilli();
		FederatedNodeDescriptor d = FederationStatement.asDescriptor(withContent(Map.of("services", List.of(),
				"mode", "open", "nodeId", Id.random().getBytes(), "issuedAt", 1L, "expiresAt", forever)), node.getId());
		assertEquals(node.getId(), d.getNodeId());
		assertEquals(Purpose.NODE_DESCRIPTOR.maxLifetime(), Duration.between(d.getIssuedAt(), d.getExpiresAt()));
	}

	@Test
	void theSignedContentCarriesNoNodeOrDatesAndIdsAsBytes() throws Exception {
		byte[] s = FederationStatement.nodeDescriptor(node, full());
		Map<String, Object> content = FederationStatement.verifier(Purpose.NODE_DESCRIPTOR).verify(s).getContent();
		assertFalse(content.containsKey("nodeId"));
		assertFalse(content.containsKey("issuedAt"));
		assertFalse(content.containsKey("expiresAt"));
		assertEquals("mutual", content.get("mode"));

		Map<?, ?> service = (Map<?, ?>) ((List<?>) content.get("services")).get(0);
		assertArrayEquals(messagingPeer.getBytes(), (byte[]) service.get("peerId"));
		assertEquals(Boolean.TRUE, service.get("federation"));
	}

	@Test
	void theJsonFormNamesIdsInBase58AndReadsBack() throws Exception {
		FederatedNodeDescriptor signed = FederationStatement.asDescriptor(
				FederationStatement.nodeDescriptor(node, full()), node.getId());
		String json = Json.toString(signed);
		Map<String, Object> map = Json.parse(json);
		assertEquals(node.getId().toBase58String(), map.get("nodeId"));
		assertEquals("mutual", map.get("mode"));
		assertEquals(signed.getIssuedAt().toEpochMilli(), ((Number) map.get("issuedAt")).longValue());
		Map<?, ?> service = (Map<?, ?>) ((List<?>) map.get("services")).get(0);
		assertEquals(messagingPeer.toBase58String(), service.get("peerId"));

		FederatedNodeDescriptor back = Json.parse(json, FederatedNodeDescriptor.class);
		assertEquals(signed.getNodeId(), back.getNodeId());
		assertEquals(signed.getMode(), back.getMode());
		assertEquals(signed.getServices(), back.getServices());
		assertEquals(signed.getIssuedAt(), back.getIssuedAt());
		assertEquals(signed.getExpiresAt(), back.getExpiresAt());

		// A built one has no dates to show; an empty list of services still shows.
		Map<String, Object> unsigned = Json.parse(Json.toString(FederatedNodeDescriptor.builder(node.getId()).build()));
		assertFalse(unsigned.containsKey("issuedAt"));
		assertEquals(List.of(), unsigned.get("services"));
	}

	@Test
	void aNodeSignsOnlyItsOwnDescriptor() {
		FederatedNodeDescriptor other = FederatedNodeDescriptor.builder(Id.random()).build();
		assertThrows(IllegalArgumentException.class, () -> FederationStatement.nodeDescriptor(node, other));
	}

	@Test
	void aDescriptorLivesADayAtMost() {
		FederatedNodeDescriptor own = FederatedNodeDescriptor.builder(node.getId()).build();
		assertThrows(IllegalArgumentException.class, () ->
				FederationStatement.nodeDescriptor(node, own, Duration.ofDays(2)));
		assertThrows(IllegalArgumentException.class, () ->
				FederationStatement.nodeDescriptor(node, own, Duration.ZERO));
	}
}
