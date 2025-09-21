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
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Random;

public class StoreValueTests extends MessageTests {
	private static Stream<Arguments> requestParameters() throws Exception {
		Value immutable = Value.createValue("This is a immutable value".getBytes());
		Value signedValue = Value.of(Id.random(), Random.randomBytes(24), 3, Random.randomBytes(64), "This is a signed value".getBytes());
		Value encryptedValue = Value.of(Id.random(), Id.random(), Random.randomBytes(24), 9, Random.randomBytes(64), "This is a encrypted value".getBytes());

		return Stream.of(
			Arguments.of("immutable", immutable, 62),
			Arguments.of("signed", signedValue, 202),
			Arguments.of("encrypted", encryptedValue, 244)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	public void testRequest(String name, Value value, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var token = 0x87654321;
		var cas = value.isMutable() ? value.getSequenceNumber() - 1 : -1;

		var msg = Message.storeValueRequest(value, token, cas);
		msg.setId(nodeId);
		var bin = msg.toBytes();

		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.STORE_VALUE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(token, msg.getBody().getToken());
		assertEquals(cas, msg.getBody().getExpectedSequenceNumber());
		assertEquals(value, msg.getBody().getValue());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	public void testRequestWithoutCas(String name, Value value, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var token = 0x87654321;
		var cas = -1;

		var msg = Message.storeValueRequest(value, token, cas);
		msg.setId(nodeId);
		var bin = msg.toBytes();

		printMessage(msg);

		int expected = value.isMutable() ? expectedSize - 5 : expectedSize;
		assertEquals(expected, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.STORE_VALUE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(token, msg.getBody().getToken());
		assertEquals(cas, msg.getBody().getExpectedSequenceNumber());
		assertEquals(value, msg.getBody().getValue());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void testResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;
		var msg = Message.storeValueResponse(txid);
		msg.setId(nodeId);

		var bin = msg.toBytes();
		printMessage(msg);

		assertEquals(20, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.STORE_VALUE, msg.getMethod());
		assertEquals(txid, msg.getTxid());
		assertNull(msg.getBody());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() throws Exception {
		var nodeId = Id.random();
		var token = 0x87654321;
		Value value = Value.of(Id.random(), Id.random(), Random.randomBytes(24), 9,
				Random.randomBytes(64), Random.randomBytes(512));

		// warmup
		var msg = Message.storeValueRequest(value, token, 8);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.storeValueRequest(value, token, 8);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> StoreValueRequest: %dms, estimated: streaming ~= 800ms, *mapping ~= 1500ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;

		// warmup
		var msg = Message.storeValueResponse(txid);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.storeValueResponse(txid);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> StoreValueResponse: %dms, estimated: streaming ~= 360ms, *mapping ~= 240ms @ MBP-13-m1pro\n", (end - start));
	}
}