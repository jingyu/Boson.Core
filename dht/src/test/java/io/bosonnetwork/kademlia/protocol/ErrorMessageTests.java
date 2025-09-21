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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.bosonnetwork.Id;

public class ErrorMessageTests extends MessageTests {
	@SuppressWarnings("SpellCheckingInspection")
	@ParameterizedTest
	@ValueSource(strings = {
			"Test error message",
			"错误信息；エラーメッセージ；에러 메시지；Chybová zpráva testu"
	})
	void testError(String errorMessage) throws Exception {
		var nodeId = Id.random();
		var txid = 0xF7654321;
		var code = 0x87654321;

		var msg = Message.error(Message.Method.PING, txid, code, errorMessage);
		msg.setId(nodeId);
		var bin = msg.toBytes();

		printMessage(msg);

		// CBOR Spec:
		// 0x60..0x77	UTF-8 string (0x00..0x17 bytes follow)
		// 0x78			UTF-8 string (one-byte uint8_t for n, and then n bytes follow)
		var size = errorMessage.getBytes().length + 33 + (errorMessage.getBytes().length > 0x17 ? 1 : 0);
		assertEquals(size, bin.length);

		assertEquals(Message.Type.ERROR, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(code, msg.getBody().getCode());
		assertEquals(errorMessage, msg.getBody().getMessage());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingError() {
		var nodeId = Id.random();
		var errorMessage = "This is a test error message.";
		var code = 0x1234;
		var txid = 0x76543210;

		// warmup
		var msg = Message.error(Message.Method.PING, txid, code, errorMessage);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			var msg2 = Message.error(Message.Method.PING, txid, code, errorMessage);
			msg2.setId(nodeId);
			bin = msg2.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> Error: %dms, estimated: streaming ~= 550ms, *mapping ~= 550ms @ MBP-13-m1pro\n", (end - start));
	}
}