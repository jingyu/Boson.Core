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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.Random;

public class FindPeerTests extends MessageTests {
	private static Stream<Arguments> requestParameters() {
		return Stream.of(
				Arguments.of("v4", true, false),
				Arguments.of("v6", false, true),
				Arguments.of("v4+v6", true, true)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	void testRequest(String name, boolean want4, boolean want6) throws Exception {
		var nodeId = Id.random();
		var target = Id.random();
		var msg = Message.findPeerRequest(target, want4, want6);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(63, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(target, msg.getBody().getTarget());
		assertEquals(want4, msg.getBody().doesWant4());
		assertEquals(want6, msg.getBody().doesWant6());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> responseParameters() {
		var ip4 = "192.168.1.1";
		var port = 65535;

		var nodes4 = new ArrayList<NodeInfo>();
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));

		var ip6 = "2001:0db8:85a3:8070:6543:8a2e:0370:7386";

		var nodes6 = new ArrayList<NodeInfo>();
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));

		var peerId = Id.random();
		var sig = Random.randomBytes(64);
		var peers = new ArrayList<PeerInfo>();
		peers.add(PeerInfo.of(peerId, Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, "http://abc.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port--, "https://foo.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port, "http://bar.example.com/", sig));

		return Stream.of(
				Arguments.of("v4", nodes4, null, null, 380),
				Arguments.of("v4+peers", nodes4, null, peers, 1093),
				Arguments.of("v6", null, nodes6, null, 476),
				Arguments.of("v6+peers", null, nodes6, peers, 1189),
				Arguments.of("v4+v6", nodes4, nodes6, null, 832),
				Arguments.of("v4+v6+peers", nodes4, nodes6, peers, 1545),
				Arguments.of("peers", null, null, peers, 737)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, List<PeerInfo> peers, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210L;

		var msg = Message.findPeerResponse(txid, nodes4, nodes6, peers);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());

		if (nodes4 != null)
			assertEquals(nodes4, msg.getBody().getNodes4());
		else
			assertTrue(msg.getBody().getNodes4().isEmpty());

		if (nodes6 != null)
			assertEquals(nodes6, msg.getBody().getNodes6());
		else
			assertTrue(msg.getBody().getNodes6().isEmpty());

		if (peers != null)
			assertEquals(peers, msg.getBody().getPeers());
		else
			assertTrue(msg.getBody().getPeers().isEmpty());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() {
		var nodeId = Id.random();
		var target = Id.random();

		// warmup
		var msg = Message.findPeerRequest(target, true, false);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findPeerRequest(target, true, false);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindPeerRequest: %dms, estimated: streaming ~= 540ms, *mapping ~= 700ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() {
		var nodeId = Id.random();
		var txid = 0x76543210;

		int port = 65535;

		var peerId = Id.random();
		var sig = Random.randomBytes(64);
		var peers = new ArrayList<PeerInfo>();
		peers.add(PeerInfo.of(peerId, Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, "http://abc.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port--, "https://foo.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port, "http://bar.example.com/", sig));

		// warmup
		var msg = Message.findPeerResponse(txid, null, null, peers);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findPeerResponse(txid, null, null, peers);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindPeerResponse: %dms, estimated: streaming ~= 1360ms, *mapping ~= 2850ms @ MBP-13-m1pro\n", (end - start));
	}
}