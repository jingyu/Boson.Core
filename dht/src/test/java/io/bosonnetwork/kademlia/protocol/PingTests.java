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

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

public class PingTests extends MessageTests {
	@Test
	void testRequest() throws Exception {
		var nodeId = Id.random();
		var msg = Message.pingRequest();
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(20, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());

		var msg2 = Message.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void testResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x78901234;
		var msg = Message.pingResponse(txid);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(20, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() {
		var nodeId = Id.random();

		// warmup
		var msg = Message.pingRequest();
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.pingRequest();
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> PingRequest: %dms, estimated: streaming ~= 390ms, *mapping ~= 240ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() {
		var nodeId = Id.random();
		var txid = 0x78901234;

		// warmup
		var msg = Message.pingResponse(txid);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.pingResponse(txid);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> PingResponse: %dms, estimated: streaming ~= 360ms, *mapping ~= 240ms @ MBP-13-m1pro\n", (end - start));
	}
}