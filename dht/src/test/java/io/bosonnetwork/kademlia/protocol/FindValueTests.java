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
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;

public class FindValueTests extends MessageTests {
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
		var seq = 0x78654321;
		var msg = Message.findValueRequest(target, want4, want6, seq);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(72, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(target, msg.getBody().getTarget());
		assertEquals(want4, msg.getBody().doesWant4());
		assertEquals(want6, msg.getBody().doesWant6());
		assertEquals(seq, msg.getBody().getExpectedSequenceNumber());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	void testRequestWithoutCas(String name, boolean want4, boolean want6) throws Exception {
		var nodeId = Id.random();
		var target = Id.random();
		var seq = -1;
		var msg = Message.findValueRequest(target, want4, want6, seq);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(63, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(target, msg.getBody().getTarget());
		assertEquals(want4, msg.getBody().doesWant4());
		assertEquals(want6, msg.getBody().doesWant6());
		assertEquals(seq, msg.getBody().getExpectedSequenceNumber());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> responseParameters() throws Exception {
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
		nodes6.add(new NodeInfo(Id.random(), ip6, port));

		Value immutable = Value.createValue("This is a immutable value".getBytes());
		Value signedValue = Value.createSignedValue("This is a signed value".getBytes());
		Value encryptedValue = Value.createEncryptedValue(Id.of(Signature.KeyPair.random().publicKey().bytes()),
				"This is a encrypted value".getBytes());

		return Stream.of(
				Arguments.of("v4", nodes4, null, null, 380),
				Arguments.of("v4+immutable", nodes4, null, immutable, 409),
				Arguments.of("v4+signed", nodes4, null, signedValue, 539),
				Arguments.of("v4+encrypted", nodes4, null, encryptedValue, 597),
				Arguments.of("v6", null, nodes6, null, 476),
				Arguments.of("v6+immutable", null, nodes6, immutable, 505),
				Arguments.of("v6+signed", null, nodes6, signedValue, 635),
				Arguments.of("v6+encrypted", null, nodes6, encryptedValue, 693),
				Arguments.of("v4+v6", nodes4, nodes6, null, 832),
				Arguments.of("v4+v6+immutable", nodes4, nodes6, immutable, 861),
				Arguments.of("v4+v6+signed", nodes4, nodes6, signedValue, 991),
				Arguments.of("v4+v6+encrypted", nodes4, nodes6, encryptedValue, 1049),
				Arguments.of("immutable", null, null, immutable, 53),
				Arguments.of("signed", null, null, signedValue, 183),
				Arguments.of("encrypted", null, null, encryptedValue, 241));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, Value value, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;
		var msg = Message.findValueResponse(txid, nodes4, nodes6, value);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
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

		assertEquals(value, msg.getBody().getValue());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() {
		var nodeId = Id.random();
		var target = Id.random();
		var seq = 0x78654321;

		// warmup
		var msg = Message.findValueRequest(target, true, false, seq);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findValueRequest(target, true, false, seq);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindValueRequest: %dms,estimated: streaming ~= 570ms, *mapping ~= 780ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		Value value = Value.createEncryptedValue(Id.of(Signature.KeyPair.random().publicKey().bytes()), Random.randomBytes(512));

		// warmup
		var msg = Message.findValueResponse(txid, null, null, value);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findValueResponse(txid, null, null, value);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindValueResponse: %dms, estimated: streaming ~= 700ms, *mapping ~= 1400ms @ MBP-13-m1pro\n", (end - start));
	}
}