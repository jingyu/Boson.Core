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

package io.bosonnetwork.kademlia.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.Random;

public class AnnouncePeerTests extends MessageTests {
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
		var token = 0x76543210;
		var msg = Message.announcePeerRequest(peer, token);
		msg.setId(peer.getNodeId());

		var bin = msg.toBytes();
		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.ANNOUNCE_PEER, msg.getMethod());
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
	void timingRequest() {
		byte[] sig = Random.randomBytes(64);
		PeerInfo peer = PeerInfo.of(Id.random(), Id.random(), 65535, sig);
		var token = 0x76543210;

		var msg = Message.announcePeerRequest(peer, token);
		msg.setId(peer.getNodeId());
		var bin = msg.toBytes();
		Message.parse(bin, peer.getNodeId());

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			var msg2 = Message.announcePeerRequest(peer, token);
			msg2.setId(peer.getNodeId());
			var bin2 = msg2.toBytes();
			Message.parse(bin2, peer.getNodeId());
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> AnnouncePeerRequest: %dms, estimated: streaming ~= 610ms, *mapping ~= 1300ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() {
		var nodeId = Id.random();
		var txid = 0x78901234;

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