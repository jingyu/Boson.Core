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

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.messages.Message.Method;
import io.bosonnetwork.kademlia.messages.Message.Type;
import io.bosonnetwork.kademlia.messages2.Message2;

public class StoreValueTests extends MessageTests {
	@Deprecated
	@Test
	public void testStoreValueRequestSize() throws Exception {
		byte[] data = new byte[1025];
		Arrays.fill(data, (byte)'D');

		Value value = Value.of(data);

		StoreValueRequest msg = new StoreValueRequest();
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);
		msg.setToken(0x88888888);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testStoreSignedValueRequestSize() throws Exception {
		byte[] nonce = new byte[24];
		Arrays.fill(nonce, (byte)'N');
		byte[] sig = new byte[64];
		Arrays.fill(sig, (byte)'S');
		byte[] data = new byte[1025];
		Arrays.fill(data, (byte)'D');
		int seq = 0x77654321;

		Value value = Value.of(Id.random(), nonce, seq, sig, data);
		StoreValueRequest msg = new StoreValueRequest();
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);
		msg.setToken(0x88888888);
		msg.setExpectedSequenceNumber(seq - 1);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testStoreEncryptedValueRequestSize() throws Exception {
		byte[] nonce = new byte[24];
		Arrays.fill(nonce, (byte)'N');
		byte[] sig = new byte[64];
		Arrays.fill(sig, (byte)'S');
		byte[] data = new byte[1025];
		Arrays.fill(data, (byte)'D');
		int seq = 0x77654321;

		Value value = Value.of(Id.random(), Id.random(), nonce, seq, sig, data);
		StoreValueRequest msg = new StoreValueRequest();
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);
		msg.setToken(0x88888888);
		msg.setExpectedSequenceNumber(seq - 1);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testStoreValueRequest() throws Exception {
		Id nodeId = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		int token = Random.random().nextInt();
		byte[] data = new byte[1025];
		Random.random().nextBytes(data);

		Value value = Value.of(data);

		StoreValueRequest msg = new StoreValueRequest();
		msg.setId(nodeId);
		msg.setTxid(txid);
		msg.setVersion(VERSION);
		msg.setToken(token);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(nodeId);
		assertInstanceOf(StoreValueRequest.class, pm);
		StoreValueRequest m = (StoreValueRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.STORE_VALUE, m.getMethod());
		assertEquals(nodeId, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(token, m.getToken());
		Value v = m.getValue();
		assertNotNull(v);
		assertEquals(value, v);

		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.StoreValueRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(msg.getExpectedSequenceNumber(), body.getExpectedSequenceNumber());
		assertEquals(msg.getValue(), body.getValue());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testStoreSignedValueRequest() throws Exception {
		Id nodeId = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		Id pk = Id.random();
		byte[] nonce = new byte[24];
		Random.random().nextBytes(nonce);
		int cas = Random.random().nextInt(0x7FFFFFFF);
		int seq = cas + 1;
		byte[] sig = new byte[64];
		Random.random().nextBytes(sig);
		int token = Random.random().nextInt();
		byte[] data = new byte[1025];
		Random.random().nextBytes(data);

		Value value = Value.of(pk, nonce, seq, sig, data);
		StoreValueRequest msg = new StoreValueRequest();
		msg.setId(nodeId);
		msg.setTxid(txid);
		msg.setVersion(VERSION);
		msg.setToken(token);
		msg.setExpectedSequenceNumber(cas);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(nodeId);
		assertInstanceOf(StoreValueRequest.class, pm);
		StoreValueRequest m = (StoreValueRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.STORE_VALUE, m.getMethod());
		assertEquals(nodeId, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(token, m.getToken());
		assertEquals(cas, m.getExpectedSequenceNumber());
		Value v = m.getValue();
		assertNotNull(v);
		assertEquals(value, v);

		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.StoreValueRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(msg.getExpectedSequenceNumber(), body.getExpectedSequenceNumber());
		assertEquals(msg.getValue(), body.getValue());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testStoreEncryptedValueRequest() throws Exception {
		Id nodeId = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		Id pk = Id.random();
		Id recipient = Id.random();
		byte[] nonce = new byte[24];
		Random.random().nextBytes(nonce);
		int cas = Random.random().nextInt(0x7FFFFFFF);
		int seq = cas + 1;
		byte[] sig = new byte[64];
		Random.random().nextBytes(sig);
		int token = Random.random().nextInt();
		byte[] data = new byte[1025];
		Random.random().nextBytes(data);

		Value value = Value.of(pk, recipient, nonce, seq, sig, data);
		StoreValueRequest msg = new StoreValueRequest();
		msg.setId(nodeId);
		msg.setTxid(txid);
		msg.setVersion(VERSION);
		msg.setToken(token);
		msg.setExpectedSequenceNumber(cas);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(nodeId);
		assertTrue(pm instanceof StoreValueRequest);
		StoreValueRequest m = (StoreValueRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.STORE_VALUE, m.getMethod());
		assertEquals(nodeId, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(token, m.getToken());
		assertEquals(cas, m.getExpectedSequenceNumber());
		Value v = m.getValue();
		assertNotNull(v);
		assertEquals(value, v);

		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.StoreValueRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(msg.getExpectedSequenceNumber(), body.getExpectedSequenceNumber());
		assertEquals(msg.getValue(), body.getValue());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testStoreValueResponseSize() throws Exception {
		StoreValueResponse msg = new StoreValueResponse(0xf7654321);
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testStoreValueResponse() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		StoreValueResponse msg = new StoreValueResponse(txid);
		msg.setId(id);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(StoreValueResponse.class, pm);
		StoreValueResponse m = (StoreValueResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.STORE_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(0, m.getVersion());

		var msg2 = (Message2<Void>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertNull(msg2.getBody());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	public void testStoreValueRequest(String name, Value value, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;
		var token = 0x87654321;
		var cas = value.isMutable() ? value.getSequenceNumber() - 1 : 0;
		var msg = new StoreValueRequest(value, token);
		msg.setId(nodeId);
		msg.setTxid(txid);
		msg.setVersion(Constants.VERSION);
		msg.setExpectedSequenceNumber(cas);

		var bin = msg.serialize();
		printMessage(msg, bin);
	}

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
		var txid = 0x76543210;
		var token = 0x87654321;
		var cas = value.isMutable() ? value.getSequenceNumber() - 1 : 0;

		var msg = Message2.storeValueRequest(txid, value, token, cas);
		msg.setId(nodeId);
		var bin = msg.toBytes();

		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message2.Type.REQUEST, msg.getType());
		assertEquals(Message2.Method.STORE_VALUE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(token, msg.getBody().getToken());
		assertEquals(cas, msg.getBody().getExpectedSequenceNumber());
		assertEquals(value, msg.getBody().getValue());

		var msg2 = Message2.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void testResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;
		var msg = Message2.storeValueResponse(txid);
		msg.setId(nodeId);

		var bin = msg.toBytes();
		printMessage(msg);

		assertEquals(20, bin.length);

		assertEquals(Message2.Type.RESPONSE, msg.getType());
		assertEquals(Message2.Method.STORE_VALUE, msg.getMethod());
		assertEquals(txid, msg.getTxid());
		assertNull(msg.getBody());

		var msg2 = Message2.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;
		var token = 0x87654321;
		Value value = Value.of(Id.random(), Id.random(), Random.randomBytes(24), 9,
				Random.randomBytes(64), Random.randomBytes(512));

		{ // TODO: remove
			var msg = new StoreValueRequest(value, token);
			msg.setId(nodeId);
			msg.setTxid(txid);
			msg.setVersion(Constants.VERSION);
			msg.setExpectedSequenceNumber(8);
			var bin = msg.serialize();
			Message.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new StoreValueRequest(value, token);
				msg.setId(nodeId);
				msg.setTxid(txid);
				msg.setVersion(Constants.VERSION);
				msg.setExpectedSequenceNumber(8);
				bin = msg.serialize();
				Message.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> StoreValueRequest: %dms\n", (end - start));
		}

		// warmup
		var msg = Message2.storeValueRequest(txid, value, token, 8);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message2.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message2.storeValueRequest(txid, value, token, 8);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message2.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> StoreValueRequest: %dms, estimated: streaming ~= 800ms, *mapping ~= 1500ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;

		{ // TODO: remove
			var msg = new StoreValueResponse(txid);
			msg.setId(nodeId);
			var bin = msg.serialize();
			Message.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new StoreValueResponse(txid);
				msg.setId(nodeId);
				bin = msg.serialize();
				Message.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> StoreValueResponse: %dms\n", (end - start));
		}

		// warmup
		var msg = Message2.storeValueResponse(txid);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message2.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message2.storeValueResponse(txid);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message2.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> StoreValueResponse: %dms, estimated: streaming ~= 360ms, *mapping ~= 240ms @ MBP-13-m1pro\n", (end - start));
	}
}