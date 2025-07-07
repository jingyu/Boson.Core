/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia.messages;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.messages.deprecated.AnnouncePeerRequest;
import io.bosonnetwork.kademlia.messages.deprecated.AnnouncePeerResponse;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Method;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Type;

public class AnnouncePeerTests extends MessageTests {
	@Deprecated
	@Test
	public void testAnnouncePeerRequestSize() throws Exception {
		byte[] sig = new byte[64];
		new SecureRandom().nextBytes(sig);
		PeerInfo peer = PeerInfo.of(Id.random(), Id.random(), 65535, sig);

		AnnouncePeerRequest msg = new AnnouncePeerRequest();
		msg.setId(peer.getNodeId());
		msg.setTxid(0x87654321);
		msg.setToken(0x88888888);
		msg.setVersion(VERSION);
		msg.setPeer(peer);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testAnnouncePeerRequestSize2() throws Exception {
		byte[] sig = new byte[64];
		new SecureRandom().nextBytes(sig);
		PeerInfo peer = PeerInfo.of(Id.random(), Id.random(), Id.random(), 65535, "https://abc.example.com/", sig);

		AnnouncePeerRequest msg = new AnnouncePeerRequest();
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setToken(0x88888888);
		msg.setVersion(VERSION);
		msg.setPeer(peer);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testAnnouncePeerRequest() throws Exception {
		Id nodeId = Id.random();
		Id peerId = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		int port = Random.random().nextInt(1, 0xFFFF);
		int token = Random.random().nextInt();
		byte[] sig = new byte[64];
		new SecureRandom().nextBytes(sig);

		PeerInfo peer = PeerInfo.of(peerId, nodeId, port, sig);

		AnnouncePeerRequest msg = new AnnouncePeerRequest();
		msg.setId(nodeId);
		msg.setTxid(txid);
		msg.setToken(token);
		msg.setVersion(VERSION);
		msg.setPeer(peer);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(nodeId);
		assertInstanceOf(AnnouncePeerRequest.class, pm);
		AnnouncePeerRequest m = (AnnouncePeerRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.ANNOUNCE_PEER, m.getMethod());
		assertEquals(nodeId, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(token, m.getToken());
		PeerInfo rPeer = m.getPeer();
		assertNotNull(rPeer);
		assertEquals(peer, rPeer);

		// Compatibility
		var msg2 = Message.parse(bin, peer.getNodeId());
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody(io.bosonnetwork.kademlia.messages.AnnouncePeerRequest.class);
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(peer, body.getPeer());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testAnnouncePeerRequest2() throws Exception {
		Id nodeId = Id.random();
		Id origin = Id.random();
		Id peerId = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		int port = Random.random().nextInt(1, 0xFFFF);
		int token = Random.random().nextInt();
		byte[] sig = new byte[64];
		new SecureRandom().nextBytes(sig);

		PeerInfo peer = PeerInfo.of(peerId, nodeId, origin, port, "http://abc.example.com/", sig);

		AnnouncePeerRequest msg = new AnnouncePeerRequest();
		msg.setId(nodeId);
		msg.setTxid(txid);
		msg.setToken(token);
		msg.setVersion(VERSION);
		msg.setPeer(peer);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(nodeId);
		assertInstanceOf(AnnouncePeerRequest.class, pm);
		AnnouncePeerRequest m = (AnnouncePeerRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.ANNOUNCE_PEER, m.getMethod());
		assertEquals(nodeId, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(token, m.getToken());
		assertEquals(peer, m.getPeer());

		// Compatibility
		var msg2 = Message.parse(bin, peer.getNodeId());
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody(io.bosonnetwork.kademlia.messages.AnnouncePeerRequest.class);
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(peer, body.getPeer());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testAnnouncePeerResponseSize() throws Exception {
		AnnouncePeerResponse msg = new AnnouncePeerResponse(0xf7654321);
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testAnnouncePeerResponse() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		AnnouncePeerResponse msg = new AnnouncePeerResponse(txid);
		msg.setId(id);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(AnnouncePeerResponse.class, pm);
		AnnouncePeerResponse m = (AnnouncePeerResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.ANNOUNCE_PEER, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(0, m.getVersion());

		// Compatibility
		var msg2 = Message.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertNull(msg2.getBody());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> requestParameters() {
		byte[] sig = Random.randomBytes(64);
		int port = 65516;

		return Stream.of(
				Arguments.of("peer", PeerInfo.of(Id.random(), Id.random(), port, sig), 144),
				Arguments.of("peerWithAltURL", PeerInfo.of(Id.random(), Id.random(), port, "http://abc.example.com/", sig), 172),
				Arguments.of("delegatedPeer", PeerInfo.of(Id.random(), Id.random(), Id.random(), port, sig), 180),
				Arguments.of("delegatedPeerWithAltURL", PeerInfo.of(Id.random(), Id.random(), Id.random(), port, "http://abc.example.com/", sig), 208)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	void testRequest(String name, PeerInfo peer, int expectedSize) throws Exception {
		var txid = 0x87654321;
		var token = 0x76543210;
		var msg = Message.announcePeerRequest(txid, peer, token);
		msg.setId(peer.getNodeId());

		var bin = msg.toBytes();
		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.ANNOUNCE_PEER, msg.getMethod());
		assertEquals(txid, msg.getTxid());
		assertEquals(token, msg.getBody().getToken());
		assertEquals(peer, msg.getBody().getPeer());

		var msg2 = Message.parse(bin, peer.getNodeId());
		msg2.setId(peer.getNodeId());

		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void testResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;
		var msg = Message.announcePeerResponse(txid);
		msg.setId(nodeId);

		var bin = msg.toBytes();
		printMessage(msg);

		assertEquals(20, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.ANNOUNCE_PEER, msg.getMethod());
		assertEquals(txid, msg.getTxid());
		assertNull(msg.getBody());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() throws Exception {
		byte[] sig = Random.randomBytes(64);
		PeerInfo peer = PeerInfo.of(Id.random(), Id.random(), 65535, sig);
	    var txid = 0x87654321;
		var token = 0x76543210;

		{ // TODO: remove
			var msg = new AnnouncePeerRequest();
			msg.setId(peer.getNodeId());
			msg.setTxid(txid);
			msg.setToken(token);
			msg.setVersion(Constants.VERSION);
			msg.setPeer(peer);

			var bin = msg.serialize();
			OldMessage.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new AnnouncePeerRequest();
				msg.setTxid(txid);
				msg.setToken(token);
				msg.setVersion(Constants.VERSION);
				msg.setPeer(peer);

				bin = msg.serialize();
				AnnouncePeerRequest m = (AnnouncePeerRequest) OldMessage.parse(bin);
				m.setId(peer.getNodeId());
				m.getPeer();
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> AnnouncePeerRequest: %dms\n", (end - start));
		}

		var msg = Message.announcePeerRequest(txid, peer, token);
		msg.setId(peer.getNodeId());
		var bin = msg.toBytes();
		Message.parse(bin, peer.getNodeId());

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			var msg2 = Message.announcePeerRequest(txid, peer, token);
			msg2.setId(peer.getNodeId());
			var bin2 = msg2.toBytes();
			Message.parse(bin2, peer.getNodeId());
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> AnnouncePeerRequest: %dms, estimated: streaming ~= 610ms, *mapping ~= 1300ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;

		{ // TODO: remove
			var msg = new AnnouncePeerResponse(txid);
			msg.setId(nodeId);
			var bin = msg.serialize();
			OldMessage.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new AnnouncePeerResponse(txid);
				msg.setId(nodeId);
				bin = msg.serialize();
				OldMessage.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> AnnouncePeerResponse: %dms\n", (end - start));
		}

		// warmup
		var msg = Message.announcePeerResponse(txid);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			var msg2 = Message.announcePeerResponse(txid);
			msg2.setId(nodeId);
			var bin2 = msg2.toBytes();
			Message.parse(bin2);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> AnnouncePeerResponse: %dms, estimated: streaming ~= 360ms, *mapping ~= 240ms @ MBP-13-m1pro\n", (end - start));
	}
}