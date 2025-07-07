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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.messages.deprecated.ErrorMessage;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Method;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Type;

public class ErrorMessageTests extends MessageTests {
	@Deprecated
	@Test
	public void testErrorMessageSize() throws Exception {
		byte[] em = new byte[1025];
		Arrays.fill(em, (byte)'E');

		ErrorMessage msg = new ErrorMessage(Method.PING, 0xF7654321, 0x87654321, new String(em));
		msg.setId(Id.random());
		msg.setVersion(VERSION);
		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testErrorMessage() throws Exception {
		int txid = Random.random().nextInt();
		int code = Random.random().nextInt();
		String error = "Test error message";

		ErrorMessage msg = new ErrorMessage(Method.PING, txid, code, error);
		msg.setId(Id.random());
		msg.setVersion(Constants.VERSION);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		assertInstanceOf(ErrorMessage.class, pm);
		ErrorMessage m = (ErrorMessage)pm;

		assertEquals(Type.ERROR, m.getType());
		assertEquals(Method.PING, m.getMethod());
		assertEquals(txid, m.getTxid());
		assertEquals("Orca/1", m.getReadableVersion());
		assertEquals(code, m.getCode());
		assertEquals(error, m.getMessage());

		// Compatibility
		var msg2 = Message.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var e = msg2.getBody(Error.class);
		assertEquals(msg.getCode(), e.getCode());
		assertEquals(msg.getMessage(), e.getMessage());
		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testErrorMessagei18n() throws Exception {
		int txid = Random.random().nextInt();
		int code = Random.random().nextInt();
		String error = "错误信息；エラーメッセージ；에러 메시지；Message d'erreur";

		ErrorMessage msg = new ErrorMessage(Method.UNKNOWN, txid, code, error);
		msg.setId(Id.random());
		msg.setVersion(VERSION);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		assertInstanceOf(ErrorMessage.class, pm);
		ErrorMessage m = (ErrorMessage)pm;

		assertEquals(Type.ERROR, m.getType());
		assertEquals(Method.UNKNOWN, m.getMethod());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(code, m.getCode());
		assertEquals(error, m.getMessage());

		// Compatibility
		var msg2 = Message.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var e = msg2.getBody(Error.class);
		assertEquals(msg.getCode(), e.getCode());
		assertEquals(error, e.getMessage());
		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"Test error message",
			"错误信息；エラーメッセージ；에러 메시지；Message d'erreur"
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
	void timingError() throws Exception {
		var nodeId = Id.random();
		var errorMessage = "This is a test error message.";
		var code = 0x1234;
		var txid = 0x76543210;

		{ // TODO: remove
			var msg = new ErrorMessage(Method.PING, txid, code, errorMessage);
			msg.setId(nodeId);
			msg.setVersion(Constants.VERSION);
			byte[] bin = msg.serialize();
			OldMessage.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new ErrorMessage(Method.PING, txid, code, errorMessage);
				msg.setId(nodeId);
				msg.setVersion(Constants.VERSION);
				bin = msg.serialize();
				OldMessage.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> Error: %dms\n", (end - start));
		}

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