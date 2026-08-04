package io.bosonnetwork;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.json.JsonContext;
import io.bosonnetwork.utils.Bytes;
import io.bosonnetwork.utils.Hex;

public class PeerInfoTests {
	@Test
	void testPeerInfo() {
		String endpoint = "tcp://203.0.113.10:5678";
		PeerInfo peer = PeerInfo.builder()
				.endpoint(endpoint)
				.build();

		assertNotNull(peer);
		assertTrue(peer.hasPrivateKey());
		assertNotNull(peer.getPrivateKey());
		assertNotNull(peer.getId());
		assertEquals(0, peer.getSequenceNumber());
		assertFalse(peer.isAuthenticated());
		assertNull(peer.getNodeId());
		assertNull(peer.getNodeSignature());
		assertEquals(0, peer.getSequenceNumber());
		assertEquals(endpoint, peer.getEndpoint());
		assertFalse(peer.hasExtra());
		assertNotNull(peer.getSignature());
		assertTrue(peer.isValid());

		String endpoint1 = "tcp://172.16.31.10:9876";
		PeerInfo peer1 = peer.update().endpoint(endpoint1).build();

		assertNotNull(peer1);
		assertTrue(peer1.hasPrivateKey());
		assertNotNull(peer1.getPrivateKey());
		assertNotNull(peer1.getId());
		assertEquals(peer.getId(), peer1.getId());
		assertEquals(1, peer1.getSequenceNumber());
		assertFalse(peer1.isAuthenticated());
		assertNull(peer1.getNodeId());
		assertNull(peer1.getNodeSignature());
		assertEquals(0, peer.getSequenceNumber());
		assertEquals(endpoint1, peer1.getEndpoint());
		assertFalse(peer1.hasExtra());
		assertNotNull(peer1.getSignature());
		assertTrue(peer1.isValid());

		String endpoint2 = "tcp://203.0.113.126:5678";
		PeerInfo peer2 = peer1.update().endpoint(endpoint2).build();

		assertNotNull(peer2);
		assertTrue(peer2.hasPrivateKey());
		assertNotNull(peer2.getPrivateKey());
		assertNotNull(peer2.getId());
		assertEquals(peer.getId(), peer2.getId());
		assertEquals(2, peer2.getSequenceNumber());
		assertFalse(peer2.isAuthenticated());
		assertNull(peer2.getNodeId());
		assertNull(peer2.getNodeSignature());
		assertEquals(0, peer.getSequenceNumber());
		assertEquals(endpoint2, peer2.getEndpoint());
		assertFalse(peer2.hasExtra());
		assertNotNull(peer2.getSignature());
		assertTrue(peer2.isValid());

		PeerInfo peer3 = peer2.update().endpoint(endpoint2).build();
		assertNotSame(peer2, peer3);
		assertEquals(endpoint2, peer3.getEndpoint());
		assertTrue(peer3.hasPrivateKey());
		assertFalse(peer3.isAuthenticated());
		assertFalse(peer3.hasExtra());
		assertEquals(3, peer3.getSequenceNumber());

		PeerInfo peer4 = peer3.withoutPrivateKey();
		assertFalse(peer4.hasPrivateKey());
		assertEquals(peer3, peer4);
		assertThrows(IllegalStateException.class, () -> peer4.update().endpoint("tcp://hostname:2345").build());

		peer.getSignature()[0] = (byte) (peer.getSignature()[0] + 1);
		assertTrue(peer.isValid());
	}

	@Test
	void testPeerInfoWithExtraData() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("foo", "bar");
		extra.put("baz", 123);
		extra.put("qux", true);
		extra.put("quux", Random.randomBytes(64));
		String endpoint = "tcp://203.0.113.10:5678";
		PeerInfo peer = PeerInfo.builder()
				.endpoint(endpoint)
				.extra(extra)
				.fingerprint(10)
				.build();

		assertNotNull(peer);
		assertTrue(peer.hasPrivateKey());
		assertNotNull(peer.getPrivateKey());
		assertNotNull(peer.getId());
		assertEquals(0, peer.getSequenceNumber());
		assertFalse(peer.isAuthenticated());
		assertNull(peer.getNodeId());
		assertNull(peer.getNodeSignature());
		assertEquals(10, peer.getFingerprint());
		assertEquals(endpoint, peer.getEndpoint());
		assertTrue(peer.hasExtra());
		assertArrayEquals(Json.toBytes(extra), peer.getExtraData());
		assertNotNull(peer.getSignature());
		assertTrue(peer.isValid());

		Map<String, Object> extra1 = new LinkedHashMap<>();
		extra1.put("foo", "baz");
		extra1.put("qux", false);
		String endpoint1 = "tcp://172.16.31.10:9876";
		PeerInfo peer1 = peer.update().endpoint(endpoint1).extra(extra1).build();

		assertNotNull(peer1);
		assertTrue(peer1.hasPrivateKey());
		assertNotNull(peer1.getPrivateKey());
		assertNotNull(peer1.getId());
		assertEquals(peer.getId(), peer1.getId());
		assertEquals(1, peer1.getSequenceNumber());
		assertFalse(peer1.isAuthenticated());
		assertNull(peer1.getNodeId());
		assertNull(peer1.getNodeSignature());
		assertEquals(10, peer.getFingerprint());
		assertEquals(endpoint1, peer1.getEndpoint());
		assertTrue(peer1.hasExtra());
		assertEquals(extra1, peer1.getExtra());
		assertNotNull(peer1.getSignature());
		assertTrue(peer1.isValid());

		byte[] extraData2 = Random.randomBytes(128);
		String endpoint2 = "tcp://203.0.113.126:5678";
		PeerInfo peer2 = peer1.update().endpoint(endpoint2).extra(extraData2).build();

		assertNotNull(peer2);
		assertTrue(peer2.hasPrivateKey());
		assertNotNull(peer2.getPrivateKey());
		assertNotNull(peer2.getId());
		assertEquals(peer.getId(), peer2.getId());
		assertEquals(2, peer2.getSequenceNumber());
		assertFalse(peer2.isAuthenticated());
		assertNull(peer2.getNodeId());
		assertNull(peer2.getNodeSignature());
		assertEquals(10, peer.getFingerprint());
		assertEquals(endpoint2, peer2.getEndpoint());
		assertTrue(peer2.hasExtra());
		assertArrayEquals(extraData2, peer2.getExtraData());
		assertNotNull(peer2.getSignature());
		assertTrue(peer2.isValid());

		PeerInfo peer3 = peer2.update().endpoint(endpoint2).extra(extraData2).build();
		assertNotSame(peer2, peer3);
		assertEquals(endpoint2, peer3.getEndpoint());
		assertFalse(peer3.isAuthenticated());
		assertArrayEquals(extraData2, peer3.getExtraData());
		assertEquals(3, peer3.getSequenceNumber());
		assertEquals(10, peer3.getFingerprint());
		assertTrue(peer3.hasPrivateKey());

		PeerInfo peer4 = peer3.withoutPrivateKey();
		assertFalse(peer4.hasPrivateKey());
		assertEquals(peer3, peer4);
		assertThrows(IllegalStateException.class, () -> peer4.update().endpoint("tcp://hostname:2345").build());

		peer.getExtraData()[0] = (byte) (peer.getExtraData()[0] + 1);
		assertTrue(peer.isValid());
	}

	@Test
	void testAuthenticatedPeerInfo() {
		Identity node = new CryptoIdentity();

		String endpoint = "tcp://203.0.113.10:5678";
		PeerInfo peer = PeerInfo.builder()
				.node(node)
				.endpoint(endpoint)
				.build();

		assertNotNull(peer);
		assertTrue(peer.hasPrivateKey());
		assertNotNull(peer.getPrivateKey());
		assertNotNull(peer.getId());
		assertEquals(0, peer.getSequenceNumber());
		assertTrue(peer.isAuthenticated());
		assertEquals(node.getId(), peer.getNodeId());
		assertNotNull(peer.getNodeSignature());
		assertEquals(0, peer.getSequenceNumber());
		assertEquals(endpoint, peer.getEndpoint());
		assertFalse(peer.hasExtra());
		assertNotNull(peer.getSignature());
		assertTrue(peer.isValid());

		String endpoint1 = "tcp://172.16.31.10:9876";
		PeerInfo peer1 = peer.update().node(node).endpoint(endpoint1).build();

		assertNotNull(peer1);
		assertTrue(peer1.hasPrivateKey());
		assertNotNull(peer1.getPrivateKey());
		assertNotNull(peer1.getId());
		assertEquals(peer.getId(), peer1.getId());
		assertEquals(1, peer1.getSequenceNumber());
		assertTrue(peer.isAuthenticated());
		assertEquals(node.getId(), peer.getNodeId());
		assertNotNull(peer.getNodeSignature());
		assertEquals(0, peer.getSequenceNumber());
		assertEquals(endpoint1, peer1.getEndpoint());
		assertFalse(peer1.hasExtra());
		assertNotNull(peer1.getSignature());
		assertTrue(peer1.isValid());

		String endpoint2 = "tcp://203.0.113.126:5678";
		PeerInfo peer2 = peer1.update().node(node).endpoint(endpoint2).build();

		assertNotNull(peer2);
		assertTrue(peer2.hasPrivateKey());
		assertNotNull(peer2.getPrivateKey());
		assertNotNull(peer2.getId());
		assertEquals(peer.getId(), peer2.getId());
		assertEquals(2, peer2.getSequenceNumber());
		assertTrue(peer.isAuthenticated());
		assertEquals(node.getId(), peer.getNodeId());
		assertNotNull(peer.getNodeSignature());
		assertEquals(0, peer.getSequenceNumber());
		assertEquals(endpoint2, peer2.getEndpoint());
		assertFalse(peer2.hasExtra());
		assertNotNull(peer2.getSignature());
		assertTrue(peer2.isValid());

		PeerInfo peer3 = peer2.update().node(node).endpoint(endpoint2).build();
		assertNotSame(peer2, peer3);
		assertEquals(node.getId(), peer3.getNodeId());
		assertEquals(endpoint2, peer3.getEndpoint());
		assertTrue(peer3.isAuthenticated());
		assertEquals(3, peer3.getSequenceNumber());

		PeerInfo peer4 = peer3.withoutPrivateKey();
		assertFalse(peer4.hasPrivateKey());
		assertEquals(peer3, peer4);
		assertThrows(IllegalStateException.class, () -> peer4.update().node(node).endpoint("tcp://hostname:2345").build());
		assertThrows(IllegalStateException.class, () -> peer3.update().endpoint(endpoint2).build());
		assertThrows(IllegalArgumentException.class, () -> peer3.update().node(new CryptoIdentity()).endpoint(endpoint2).build());
		assertThrows(IllegalArgumentException.class, () -> peer3.update().identity(new CryptoIdentity()).node(node).endpoint(endpoint2).build());

		peer.getSignature()[0] = (byte) (peer.getSignature()[0] + 1);
		assertTrue(peer.isValid());
	}

	@Test
	void testAuthenticatedPeerInfoWithExtraData() {
		Identity node = new CryptoIdentity();

		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("foo", "bar");
		extra.put("baz", 123);
		extra.put("qux", true);
		extra.put("quux", Random.randomBytes(64));
		String endpoint = "tcp://203.0.113.10:5678";
		PeerInfo peer = PeerInfo.builder()
				.keepPrivateKey()
				.node(node)
				.fingerprint(-57)
				.endpoint(endpoint)
				.extra(extra)
				.build();

		assertNotNull(peer);
		assertTrue(peer.hasPrivateKey());
		assertNotNull(peer.getPrivateKey());
		assertNotNull(peer.getId());
		assertEquals(0, peer.getSequenceNumber());
		assertTrue(peer.isAuthenticated());
		assertEquals(node.getId(), peer.getNodeId());
		assertNotNull(peer.getNodeSignature());
		assertEquals(-57, peer.getFingerprint());
		assertEquals(endpoint, peer.getEndpoint());
		assertTrue(peer.hasExtra());
		assertArrayEquals(Json.toBytes(extra), peer.getExtraData());
		assertNotNull(peer.getSignature());
		assertTrue(peer.isValid());

		Map<String, Object> extra1 = new LinkedHashMap<>();
		extra1.put("foo", "baz");
		extra1.put("qux", false);
		String endpoint1 = "tcp://172.16.31.10:9876";
		PeerInfo peer1 = peer.update().node(node).endpoint(endpoint1).extra(extra1).build();

		assertNotNull(peer1);
		assertTrue(peer1.hasPrivateKey());
		assertNotNull(peer1.getPrivateKey());
		assertNotNull(peer1.getId());
		assertEquals(peer.getId(), peer1.getId());
		assertEquals(1, peer1.getSequenceNumber());
		assertTrue(peer.isAuthenticated());
		assertEquals(node.getId(), peer.getNodeId());
		assertNotNull(peer.getNodeSignature());
		assertEquals(-57, peer.getFingerprint());
		assertEquals(endpoint1, peer1.getEndpoint());
		assertTrue(peer1.hasExtra());
		assertEquals(extra1, peer1.getExtra());
		assertNotNull(peer1.getSignature());
		assertTrue(peer1.isValid());

		byte[] extraData2 = Random.randomBytes(128);
		String endpoint2 = "tcp://203.0.113.126:5678";
		PeerInfo peer2 = peer1.update().node(node).endpoint(endpoint2).extra(extraData2).build();

		assertNotNull(peer2);
		assertTrue(peer2.hasPrivateKey());
		assertNotNull(peer2.getPrivateKey());
		assertNotNull(peer2.getId());
		assertEquals(peer.getId(), peer2.getId());
		assertEquals(2, peer2.getSequenceNumber());
		assertTrue(peer.isAuthenticated());
		assertEquals(node.getId(), peer.getNodeId());
		assertNotNull(peer.getNodeSignature());
		assertEquals(-57, peer.getFingerprint());
		assertEquals(endpoint2, peer2.getEndpoint());
		assertTrue(peer2.hasExtra());
		assertArrayEquals(extraData2, peer2.getExtraData());
		assertNotNull(peer2.getSignature());
		assertTrue(peer2.isValid());

		PeerInfo peer3 = peer2.update().node(node).endpoint(endpoint2).extra(extraData2).build();
		assertNotSame(peer2, peer3);
		assertEquals(node.getId(), peer3.getNodeId());
		assertEquals(-57, peer3.getFingerprint());
		assertEquals(endpoint2, peer3.getEndpoint());
		assertArrayEquals(extraData2, peer3.getExtraData());
		assertTrue(peer3.hasPrivateKey());
		assertTrue(peer3.isAuthenticated());
		assertTrue(peer3.hasExtra());
		assertEquals(3, peer3.getSequenceNumber());

		PeerInfo peer4 = peer3.withoutPrivateKey();
		assertFalse(peer4.hasPrivateKey());
		assertEquals(peer3, peer4);
		assertThrows(IllegalStateException.class, () -> peer4.update().node(node).endpoint("tcp://hostname:2345").build());
		assertThrows(IllegalStateException.class, () -> peer3.update().endpoint(endpoint2).build());
		assertThrows(IllegalArgumentException.class, () -> peer3.update().node(new CryptoIdentity()).endpoint(endpoint2).build());
		assertThrows(IllegalArgumentException.class, () -> peer3.update().identity(new CryptoIdentity()).node(node).endpoint(endpoint2).build());

		peer.getExtraData()[0] = (byte) (peer.getExtraData()[0] + 1);
		assertTrue(peer.isValid());
	}

	@Test
	void testInvalidPeerInfo() {
		Id peerId = Id.random();
		byte[] sig = Random.randomBytes(Signature.BYTES);

		// Invalid sequence number
		assertThrows(IllegalArgumentException.class, () -> PeerInfo.of(peerId, -1, null, null, sig, 0, "uri", null));

		// NodeId without NodeSig
		assertThrows(IllegalArgumentException.class, () -> PeerInfo.of(peerId, 0, Id.random(), null, sig, 1, "uri", null));

		// NodeSig without NodeId
		assertThrows(IllegalArgumentException.class, () -> PeerInfo.of(peerId, 0, null, sig, sig, 2, "uri", null));
	}

	/**
	 * The node signature must cover the fingerprint and the sequence number.
	 * <p>
	 * If it covered only (peerId, nodeId) it would be a constant, and so a permanent bearer
	 * credential: whoever held the peer private key could staple one node signature onto every
	 * later version of the peer, forever, without the node ever taking part again.
	 */
	@Test
	void testNodeSignatureBindsFingerprintAndSequenceNumber() {
		Identity node = new CryptoIdentity();
		PeerInfo peer = PeerInfo.builder().node(node).endpoint("tcp://203.0.113.126:5678").build();

		assertTrue(peer.isAuthenticated());
		assertTrue(peer.isValid());

		// Recomputed here so the binding cannot be dropped from the digest without failing a test.
		byte[] digest = Hash.sha256(peer.getId().bytesUnsafe(), node.getId().bytesUnsafe(),
				Bytes.fromLong(peer.getFingerprint()), Bytes.fromInteger(peer.getSequenceNumber()));
		assertTrue(Signature.verify(digest, peer.getNodeSignature(), node.getId().toSignatureKey()));

		// A new version of the same peer instance: same fingerprint, higher sequence number.
		PeerInfo updated = peer.update().node(node).endpoint("tcp://203.0.113.126:5679").build();
		assertTrue(updated.isValid());
		assertEquals(peer.getFingerprint(), updated.getFingerprint());
		assertEquals(peer.getSequenceNumber() + 1, updated.getSequenceNumber());
		assertFalse(Arrays.equals(peer.getNodeSignature(), updated.getNodeSignature()));

		// Replaying the previous version's node signature at the new sequence number is rejected.
		PeerInfo replayed = PeerInfo.of(updated.getId(), updated.getSequenceNumber(), node.getId(),
				peer.getNodeSignature(), updated.getSignature(), updated.getFingerprint(),
				updated.getEndpoint(), updated.getExtraData());
		assertFalse(replayed.isValid());

		// Two instances of one peer, same node and sequence number, differing only by fingerprint.
		Identity owner = new CryptoIdentity();
		PeerInfo first = PeerInfo.builder().identity(owner).node(node)
				.fingerprint(1).endpoint("tcp://203.0.113.126:5678").build();
		PeerInfo second = PeerInfo.builder().identity(owner).node(node)
				.fingerprint(2).endpoint("tcp://203.0.113.126:5678").build();
		assertFalse(Arrays.equals(first.getNodeSignature(), second.getNodeSignature()));
	}

	@Test
	void testEqualsAndHashCode() {
		PeerInfo p1 = PeerInfo.builder().endpoint("tcp://203.0.113.126:5678").build();
		PeerInfo p2 = p1.withoutPrivateKey();
		PeerInfo p3 = PeerInfo.builder().endpoint("tcp://203.0.113.126:5678").build();

		assertEquals(p1, p2);
		assertEquals(p1.hashCode(), p2.hashCode());
		assertNotEquals(p1, p3); // different keys
	}

	@ParameterizedTest
	@ValueSource(strings = {"simple", "simple+omitted", "simple+extra", "simple+extra+omitted",
			"authenticated", "authenticated+omitted", "authenticated+extra", "authenticated+extra+omitted"})
	void testJson(String mode) {
		Identity nodeIdentity = new CryptoIdentity();
		Signature.KeyPair keypair = Signature.KeyPair.random();
		Id peerId = Id.of(keypair.publicKey().bytes());
		Map<String, Object> extra = Map.of("foo", 123, "bar", "hello world");

		PeerInfo pi = switch (mode) {
			case "simple", "simple+omitted" -> PeerInfo.builder()
					.key(keypair)
					.sequenceNumber(6)
					.fingerprint(1000)
					.endpoint("tcp://203.0.113.10:3456")
					.build();
			case "simple+extra", "simple+extra+omitted" -> PeerInfo.builder()
					.key(keypair)
					.sequenceNumber(7)
					.endpoint("tcp://203.0.113.10:3456")
					.extra(extra)
					.build();
			case "authenticated", "authenticated+omitted" -> PeerInfo.builder()
					.key(keypair)
					.node(nodeIdentity)
					.sequenceNumber(8)
					.endpoint("tcp://203.0.113.10:3456")
					.build();
			case "authenticated+extra", "authenticated+extra+omitted" ->  PeerInfo.builder()
					.key(keypair)
					.node(nodeIdentity)
					.fingerprint(-1234)
					.sequenceNumber(9)
					.endpoint("tcp://203.0.113.10:3456")
					.extra(extra)
					.build();
			default -> throw new AssertionError("Unknown mode: " + mode);
		};

		boolean omitted = mode.endsWith("+omitted");
		JsonContext serializeContext = omitted ? JsonContext.perCall(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true) : null;
		JsonContext deserializeContext = omitted ? JsonContext.perCall(PeerInfo.ATTRIBUTE_PEER_ID, peerId) : null;

		String json = Json.toString(pi, serializeContext);
		System.out.println(json);
		System.out.println(Json.toPrettyString(pi, serializeContext));

		PeerInfo pi2 = Json.parse(json, PeerInfo.class, deserializeContext);
		assertEquals(pi, pi2);
		String json2 = Json.toString(pi2, serializeContext);
		assertEquals(json, json2);

		if (omitted) {
			Exception e = assertThrows(MismatchedInputException.class, () ->
					Json.objectMapper().readValue(json, PeerInfo.class)
			);
			assertTrue(e.getMessage().startsWith("Invalid PeerInfo: peer id can not be null"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"simple", "simple+omitted", "simple+extra", "simple+extra+omitted",
			"authenticated", "authenticated+omitted", "authenticated+extra", "authenticated+extra+omitted"})
	void testCbor(String mode) {
		Identity nodeIdentity = new CryptoIdentity();
		Signature.KeyPair keypair = Signature.KeyPair.random();
		Id peerId = Id.of(keypair.publicKey().bytes());
		Map<String, Object> extra = Map.of("foo", 123, "bar", "hello world");

		PeerInfo pi = switch (mode) {
			case "simple", "simple+omitted" -> PeerInfo.builder()
					.key(keypair)
					.sequenceNumber(6)
					.fingerprint(1000)
					.endpoint("tcp://203.0.113.10:3456")
					.build();
			case "simple+extra", "simple+extra+omitted" -> PeerInfo.builder()
					.key(keypair)
					.sequenceNumber(7)
					.endpoint("tcp://203.0.113.10:3456")
					.extra(extra)
					.build();
			case "authenticated", "authenticated+omitted" -> PeerInfo.builder()
					.key(keypair)
					.node(nodeIdentity)
					.sequenceNumber(8)
					.endpoint("tcp://203.0.113.10:3456")
					.build();
			case "authenticated+extra", "authenticated+extra+omitted" ->  PeerInfo.builder()
					.key(keypair)
					.node(nodeIdentity)
					.fingerprint(-1234)
					.sequenceNumber(9)
					.endpoint("tcp://203.0.113.10:3456")
					.extra(extra)
					.build();
			default -> throw new AssertionError("Unknown mode: " + mode);
		};

		boolean omitted = mode.endsWith("+omitted");
		JsonContext serializeContext = omitted ? JsonContext.perCall(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true) : null;
		JsonContext deserializeContext = omitted ? JsonContext.perCall(PeerInfo.ATTRIBUTE_PEER_ID, peerId) : null;

		byte[] cbor = Json.toBytes(pi, serializeContext);
		System.out.println(Hex.encode(cbor));
		System.out.println(Json.toPrettyString(Json.parse(cbor)));

		PeerInfo pi2 = Json.parse(cbor, PeerInfo.class, deserializeContext);
		assertEquals(pi, pi2);
		byte[] cbor2 = Json.toBytes(pi2, serializeContext);
		assertArrayEquals(cbor, cbor2);

		if (omitted) {
			Exception e = assertThrows(MismatchedInputException.class, () ->
					Json.cborMapper().readValue(cbor, PeerInfo.class)
			);
			assertTrue(e.getMessage().startsWith("Invalid PeerInfo: peer id can not be null"));
		}
	}
}