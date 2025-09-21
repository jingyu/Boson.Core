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

public class FindNodeTests extends MessageTests {
	private static Stream<Arguments> requestParameters() {
		return Stream.of(
				Arguments.of("v4", true, false, false),
				Arguments.of("v4+token", true, false, true),
				Arguments.of("v6", false, true, false),
				Arguments.of("v6+token", false, true, true),
				Arguments.of("v4+v6", true, true, false),
				Arguments.of("v4+v6+token", true, true, true)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	void testRequest(String name, boolean want4, boolean want6, boolean wantToken) throws Exception {
		var nodeId = Id.random();
		var target = Id.random();
		var msg = Message.findNodeRequest(target, want4, want6, wantToken);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(63, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_NODE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(target, msg.getBody().getTarget());
		assertEquals(want4, msg.getBody().doesWant4());
		assertEquals(want6, msg.getBody().doesWant6());
		assertEquals(wantToken, msg.getBody().doesWantToken());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> responseParameters() {
		var ip4 = "192.168.1.1";
		int port = 65535;

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
		nodes6.add(new NodeInfo(Id.random(), ip6, port));

		var token = 0x87654321;

		return Stream.of(
				Arguments.of("v4", nodes4, null, 0, 380),
				Arguments.of("v4+token", nodes4, null, token, 389),
				Arguments.of("v6", null, nodes6, 0, 476),
				Arguments.of("v6+token", null, nodes6, token, 485),
				Arguments.of("v4+v6", nodes4, nodes6, 0, 832),
				Arguments.of("v4+v6+token", nodes4, nodes6, token, 841)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, int token, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		var msg = Message.findNodeResponse(txid, nodes4, nodes6, token);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_NODE, msg.getMethod());
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

		assertEquals(token, msg.getBody().getToken());

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
		var msg = Message.findNodeRequest(target, true, false, true);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findNodeRequest(target, true, false, true);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindNodeRequest: %dms, estimated: streaming ~= 560ms, *mapping ~= 900ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() {
		var nodeId = Id.random();
		var txid = 0x76543210;
		var token = 0x87654321;

		String ip4 = "251.251.251.251";
		String ip6 = "f1ee:f1ee:f1ee:f1ee:f1ee:f1ee:f1ee:f1ee";
		int port = 65535;

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port));

		// warmup
		var msg = Message.findNodeResponse(txid, nodes4, nodes6, token);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findNodeResponse(txid, nodes4, nodes6, token);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindNodeResponse: %dms, estimated: streaming ~= 2500ms, *mapping ~= 2600ms @ MBP-13-m1pro\n", (end - start));
	}
}