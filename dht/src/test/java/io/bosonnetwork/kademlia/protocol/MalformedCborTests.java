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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the wire decoder does with CBOR that no encoder would produce.
 * <p>
 * Every byte reaching {@link Message#parse(byte[], io.bosonnetwork.Id)} was chosen by an unauthenticated
 * stranger, and the properties pinned here are the ones that keep a malformed datagram from costing more
 * than its size. Two of them are behavior of the codec rather than of this code - that a length prefix
 * allocates as bytes arrive rather than when it is read, and that a huge decimal stays lazy - which is
 * exactly why they are pinned: a dependency upgrade that changed either would otherwise change this
 * protocol's exposure silently.
 * </p>
 */
class MalformedCborTests {
	/** The production wire factory, so the limits under test are the ones the node actually runs with. */
	private static final ObjectMapper wire = new ObjectMapper(Message.wireCborFactory());

	private static byte[] bytes(int... v) {
		byte[] b = new byte[v.length];
		for (int i = 0; i < v.length; i++)
			b[i] = (byte) v[i];
		return b;
	}

	private static byte[] repeat(int b, int count) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < count; i++)
			out.write(b);
		return out.toByteArray();
	}

	/**
	 * Decoded by the wire factory and refused <em>for the stated reason</em>.
	 * <p>
	 * The reason is the assertion, not the refusal. Every input here is also malformed as a message, so a
	 * test that only checked that something was thrown would pass with the limits removed entirely -
	 * which is exactly what an earlier version of this file did, and what neutering the limit exposed.
	 * </p>
	 */
	private static void refusedBy(byte[] cbor, Class<? extends Throwable> cause, String because) {
		// Preemptive, so a decoder that does not terminate fails this test rather than the suite.
		Throwable t = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
				() -> assertThrows(Throwable.class, () -> wire.readTree(cbor)));
		assertTrue(cause.isInstance(t), "refused, but not for the reason under test: " + t);
		assertTrue(String.valueOf(t.getMessage()).contains(because),
				"expected a refusal mentioning \"" + because + "\", got: " + t.getMessage());
	}

	@Test
	@DisplayName("a length prefix claiming gigabytes costs only the bytes that arrive")
	void hugeLengthPrefixesAreNotPreallocated() {
		// The classic attack on a length-prefixed codec: five bytes that ask for two gigabytes. Ending at
		// end-of-input is the property being pinned - it says the decoder sized its buffer from what it
		// read rather than from what the header claimed, which is what makes the packet size limit
		// sufficient. This is the codec's behavior rather than ours, and is pinned here so that an
		// upgrade changing it cannot change this protocol's exposure silently.
		refusedBy(bytes(0x5A, 0x7F, 0xFF, 0xFF, 0xFF), JsonEOFException.class, "end-of-input");
		refusedBy(bytes(0x7A, 0x7F, 0xFF, 0xFF, 0xFF), JsonEOFException.class, "end-of-input");
		refusedBy(bytes(0x9A, 0x7F, 0xFF, 0xFF, 0xFF), JsonEOFException.class, "end-of-input");
		refusedBy(bytes(0xBA, 0x7F, 0xFF, 0xFF, 0xFF), JsonEOFException.class, "end-of-input");
		refusedBy(bytes(0xC2, 0x5A, 0x7F, 0xFF, 0xFF, 0xFF), JsonEOFException.class, "end-of-input");
	}

	@Test
	@DisplayName("nesting is bounded by this protocol's limit, not the codec's default")
	void deepNestingIsRefused() {
		// A datagram carries far more nesting than the limit allows - one byte per level, either
		// encoding - so the limit is what stops this rather than the packet size.
		refusedBy(repeat(0x9F, 64), StreamConstraintsException.class, "nesting depth");
		refusedBy(repeat(0x81, 64), StreamConstraintsException.class, "nesting depth");

		// One past our own limit, which fails only while the limit is ours. The codec's default of 1000
		// would let this through, and a depth of 1000 fits in a datagram several times over.
		refusedBy(repeat(0x9F, 17), StreamConstraintsException.class, "nesting depth");
	}

	@Test
	@DisplayName("a number no message would carry is refused by length")
	void hugeNumbersAreRefused() {
		// A bignum whose byte string is longer than any integer this protocol writes.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xC2);                                   // tag 2, unsigned bignum
		out.write(0x58); out.write(0x40);                  // byte string, 64 bytes
		for (int i = 0; i < 64; i++)
			out.write(0xFF);
		refusedBy(out.toByteArray(), StreamConstraintsException.class, "Number value length");
	}

	@Test
	@DisplayName("the message shape gate answers before the limits do")
	void theShapeGateRefusesNonObjectsFirst() {
		// Through parse() none of the above is reachable: a message must be an object at the root, so
		// anything else is refused by the binding without the parser reading on. Pinned because it is the
		// outer layer and the cheaper one, and because it is what makes the limits a second line.
		for (byte[] cbor : new byte[][] {
				bytes(0x5A, 0x7F, 0xFF, 0xFF, 0xFF), repeat(0x9F, 64), repeat(0x81, 64),
				new byte[0], bytes(0xA3), bytes(0xFF, 0xFF, 0xFF, 0xFF), repeat(0x00, 64) })
			assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
					() -> assertThrows(IllegalArgumentException.class, () -> Message.parse(cbor)));
	}

	@Test
	@DisplayName("a real message still parses")
	void theLimitsDoNotReachRealMessages() {
		// The guard against tightening the limits past what the protocol actually sends.
		Message msg = Message.pingRequest();
		msg.setId(io.bosonnetwork.Id.random());
		Message parsed = Message.parse(msg.toBytes());
		assertEquals(msg.getMethod(), parsed.getMethod());
		assertEquals(msg.getTxid(), parsed.getTxid());
	}
}
