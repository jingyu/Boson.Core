/*
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.json.Json;

/**
 * Robustness tests for {@link Message} deserialization against malformed / hostile input.
 *
 * <p>These exercise the network attack surface: the wire deserializer must reject invalid
 * envelopes with a clear error (which {@code RpcServer} turns into a drop + suspicious flag)
 * rather than silently producing a half-constructed {@link Message}.
 */
public class MessageParseTests extends MessageTests {
	// Composite 'y' values (type | method).
	private static final int PING_REQUEST = Message.Type.REQUEST.value() | Message.Method.PING.value();
	private static final int FIND_NODE_REQUEST = Message.Type.REQUEST.value() | Message.Method.FIND_NODE.value();
	private static final int FIND_NODE_RESPONSE = Message.Type.RESPONSE.value() | Message.Method.FIND_NODE.value();
	private static final int ERROR_PING = Message.Type.ERROR.value() | Message.Method.PING.value();
	private static final int UNKNOWN_REQUEST = Message.Type.REQUEST.value() | Message.Method.UNKNOWN.value();

	/** Serialize an ordered map to CBOR so the deserializer sees fields in the given order. */
	private static byte[] cbor(Map<String, Object> fields) throws Exception {
		return Json.cborMapper().writeValueAsBytes(fields);
	}

	private static Map<String, Object> map() {
		return new LinkedHashMap<>();
	}

	@Test
	void parsePingRequestWithoutBodyIsValid() throws Exception {
		// PING uses an empty (Void) body, so the absence of 'q' is legitimate.
		var fields = map();
		fields.put("y", PING_REQUEST);
		fields.put("t", 0x12345678L);

		var msg = Message.parse(cbor(fields));
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertEquals(0x12345678L, msg.getTxid());
		assertNull(msg.getBody());
	}

	@Test
	void parseUnknownMethodWithoutBodyIsValid() throws Exception {
		// UNKNOWN uses a JsonNode catch-all body that may be absent; such messages must still parse
		// (they are received and answered with a MethodUnknown error rather than dropped).
		var fields = map();
		fields.put("y", UNKNOWN_REQUEST);
		fields.put("t", 0x12345678L);

		var msg = Message.parse(cbor(fields));
		assertEquals(Message.Method.UNKNOWN, msg.getMethod());
		assertEquals(0x12345678L, msg.getTxid());
	}

	@Test
	void parseMissingTypeFieldThrows() throws Exception {
		// No 'y': neither type nor method can be resolved.
		var fields = map();
		fields.put("t", 123L);
		var bytes = cbor(fields);
		assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes));
	}

	@Test
	void parseMissingTxidThrows() throws Exception {
		var fields = map();
		fields.put("y", PING_REQUEST);
		var bytes = cbor(fields);
		assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes));
	}

	@Test
	void parseZeroTxidThrows() throws Exception {
		var fields = map();
		fields.put("y", PING_REQUEST);
		fields.put("t", 0L);
		var bytes = cbor(fields);
		assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes));
	}

	@Test
	void parseTxidAboveTheSignedIntRangeIsValid() throws Exception {
		// The half of the id space above 0x7FFFFFFF is ordinary: this node reaches it itself once the
		// counter wraps, and it goes on the wire as a conforming CBOR unsigned integer. Reading the
		// field as a signed int rejects every id in that half - our own included - so a node that got
		// there would be answered by nobody and would parse no answer.
		for (long txid : new long[] { 0x80000000L, 0xC0000000L, 0xFFFFFFFFL }) {
			var fields = map();
			fields.put("y", PING_REQUEST);
			fields.put("t", txid);

			var msg = Message.parse(cbor(fields));
			assertEquals(txid, msg.getTxid(), "unsigned transaction id not preserved");
		}
	}

	@Test
	void parseTxidFromAPeerHoldingItInASignedIntIsValid() throws Exception {
		// An implementation that keeps the transaction id in a signed 32-bit int serializes every id
		// past 0x7FFFFFFF as a negative number. Those four bytes carry the id the peer meant, so they
		// are reinterpreted rather than refused - otherwise half of that peer's messages are dropped.
		var fields = map();
		fields.put("y", PING_REQUEST);
		fields.put("t", -5L);
		assertEquals(0xFFFFFFFBL, Message.parse(cbor(fields)).getTxid());

		fields = map();
		fields.put("y", PING_REQUEST);
		fields.put("t", (long) Integer.MIN_VALUE);
		assertEquals(0x80000000L, Message.parse(cbor(fields)).getTxid());
	}

	@Test
	void parseNegativeTxidWiderThanASignedIntThrows() throws Exception {
		// Reinterpretation covers exactly the values a signed 32-bit int can hold, so a negative wider
		// than that is not an unsigned transaction id under any reading and stays a malformed envelope.
		for (long txid : new long[] { -0x100000000L, Long.MIN_VALUE }) {
			var fields = map();
			fields.put("y", PING_REQUEST);
			fields.put("t", txid);
			var bytes = cbor(fields);
			assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes),
					"accepted an out-of-range transaction id: " + txid);
		}
	}

	@Test
	void messagesWithATxidPastTheSignedIntRangeRoundTrip() throws Exception {
		// End to end over this node's own codec, which is where the signed read broke first: every
		// message it sends past the wrap becomes unparseable, by its peers and by itself.
		for (long txid : new long[] { 1L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL }) {
			var msg = Message.pingResponse(txid);
			assertEquals(txid, Message.parse(msg.toBytes()).getTxid(),
					"round trip lost transaction id " + Long.toHexString(txid));
		}
	}

	@Test
	void parseRequestMissingBodyThrows() throws Exception {
		// FIND_NODE request mandates a 'q' body.
		var fields = map();
		fields.put("y", FIND_NODE_REQUEST);
		fields.put("t", 123L);
		var bytes = cbor(fields);
		assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes));
	}

	@Test
	void parseResponseMissingBodyThrows() throws Exception {
		// FIND_NODE response mandates an 'r' body.
		var fields = map();
		fields.put("y", FIND_NODE_RESPONSE);
		fields.put("t", 123L);
		var bytes = cbor(fields);
		assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes));
	}

	@Test
	void parseErrorMissingBodyThrows() throws Exception {
		// ERROR messages mandate an 'e' body.
		var fields = map();
		fields.put("y", ERROR_PING);
		fields.put("t", 123L);
		var bytes = cbor(fields);
		assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes));
	}

	@Test
	void parseBodyBeforeTypeThrows() throws Exception {
		// 'q' appears before 'y' - the deserializer cannot select a body class yet.
		var fields = map();
		fields.put("t", 123L);
		fields.put("q", map());
		fields.put("y", FIND_NODE_REQUEST);
		var bytes = cbor(fields);
		assertThrows(IllegalArgumentException.class, () -> Message.parse(bytes));
	}

	@Test
	void parseJsonMissingTypeFieldThrows() {
		// Same contract on the JSON path.
		assertThrows(IllegalArgumentException.class, () -> Message.parse("{\"t\":123}"));
	}
}