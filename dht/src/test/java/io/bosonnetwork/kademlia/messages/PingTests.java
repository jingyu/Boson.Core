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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.messages.deprecated.Message;
import io.bosonnetwork.kademlia.messages.deprecated.Message.Method;
import io.bosonnetwork.kademlia.messages.deprecated.Message.Type;
import io.bosonnetwork.kademlia.messages.deprecated.PingRequest;
import io.bosonnetwork.kademlia.messages.deprecated.PingResponse;

public class PingTests extends MessageTests {
	@Deprecated
	@Test
	public void testPingRequestSize() throws Exception {
		PingRequest msg = new PingRequest();
		msg.setId(Id.random());
		msg.setTxid(0xF8901234);
		msg.setVersion(VERSION);
		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testPingRequest() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		PingRequest msg = new PingRequest();
		msg.setId(id);
		msg.setTxid(txid);
		msg.setVersion(VERSION);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(PingRequest.class, pm);
		PingRequest m = (PingRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.PING, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());

		// Compatibility
		var msg2 = Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertNull(msg2.getBody());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testPingResponseSize() throws Exception {
		PingResponse msg = new PingResponse();
		msg.setId(Id.random());
		msg.setTxid(0x78901234);
		msg.setVersion(VERSION);
		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testPingResponse() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		PingResponse msg = new PingResponse(txid);
		msg.setId(id);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(PingResponse.class, pm);
		PingResponse m = (PingResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.PING, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(0, m.getVersion());

		// Compatibility
		var msg2 = Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertNull(msg2.getBody());
		assertEquals(msg.getVersion(), msg2.getVersion());

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void testRequest() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;
		var msg = Message2.pingRequest(txid);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(20, bin.length);

		assertEquals(Message2.Type.REQUEST, msg.getType());
		assertEquals(Message2.Method.PING, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());

		var msg2 = Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void testResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;
		var msg = Message2.pingResponse(txid);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(20, bin.length);

		assertEquals(Message2.Type.RESPONSE, msg.getType());
		assertEquals(Message2.Method.PING, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());

		var msg2 = Message2.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;

		{	// TODO: remove this block
			var msg = new PingRequest();
			msg.setId(nodeId);
			msg.setTxid(txid);
			msg.setVersion(Constants.VERSION);
			var bin = msg.serialize();
			Message.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new PingRequest();
				msg.setId(nodeId);
				msg.setTxid(txid);
				msg.setVersion(Constants.VERSION);
				bin = msg.serialize();
				Message.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> PingRequest: %dms\n", (end - start));
		}

		// warmup
		var msg = Message2.pingRequest(txid);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message2.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message2.pingRequest(txid);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message2.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> PingRequest: %dms, estimated: streaming ~= 390ms, *mapping ~= 240ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;

		{	// TODO: remove this block
			var msg = new PingResponse();
			msg.setId(nodeId);
			msg.setTxid(txid);
			msg.setVersion(Constants.VERSION);
			var bin = msg.serialize();
			Message.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new PingResponse();
				msg.setId(nodeId);
				msg.setTxid(txid);
				msg.setVersion(Constants.VERSION);
				bin = msg.serialize();
				Message.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> PingResponse: %dms\n", (end - start));
		}

		// warmup
		var msg = Message2.pingResponse(txid);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message2.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message2.pingResponse(txid);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message2.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> PingResponse: %dms, estimated: streaming ~= 360ms, *mapping ~= 240ms @ MBP-13-m1pro\n", (end - start));
	}
}