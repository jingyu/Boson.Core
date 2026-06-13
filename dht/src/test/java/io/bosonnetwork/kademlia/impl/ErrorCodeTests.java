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

package io.bosonnetwork.kademlia.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.kademlia.exceptions.ImmutableSubstitutionFail;
import io.bosonnetwork.kademlia.exceptions.InvalidPeer;
import io.bosonnetwork.kademlia.exceptions.InvalidToken;
import io.bosonnetwork.kademlia.exceptions.InvalidValue;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.exceptions.ProtocolError;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpected;
import io.bosonnetwork.kademlia.exceptions.SequenceNotMonotonic;

class ErrorCodeTests {
	@Test
	void valueOfRoundTripsEveryConstant() {
		// Every declared code must map back to its own constant (previously 1-5 and 400-402 fell through to Unknown).
		for (ErrorCode ec : ErrorCode.values())
			assertEquals(ec, ErrorCode.valueOf(ec.value()), "valueOf should round-trip " + ec);
	}

	@Test
	void valueOfMapsValidationErrorCodes() {
		// Regression for the 400/401/402 codes that the old switch did not handle.
		assertEquals(ErrorCode.InvalidToken, ErrorCode.valueOf(400));
		assertEquals(ErrorCode.InvalidValue, ErrorCode.valueOf(401));
		assertEquals(ErrorCode.InvalidPeer, ErrorCode.valueOf(402));
	}

	@Test
	void valueOfUnknownCodeReturnsUnknown() {
		assertEquals(ErrorCode.Unknown, ErrorCode.valueOf(99999));
	}

	@Test
	void fromErrorCodeMapsToTypedException() {
		assertInstanceOf(InvalidToken.class, KadException.fromErrorCode(400, "t"));
		assertInstanceOf(InvalidValue.class, KadException.fromErrorCode(401, "v"));
		assertInstanceOf(InvalidPeer.class, KadException.fromErrorCode(402, "p"));
		assertInstanceOf(ProtocolError.class, KadException.fromErrorCode(203, "x"));
		assertInstanceOf(SequenceNotExpected.class, KadException.fromErrorCode(301, "cas"));
		assertInstanceOf(SequenceNotMonotonic.class, KadException.fromErrorCode(302, "seq"));
		assertInstanceOf(ImmutableSubstitutionFail.class, KadException.fromErrorCode(303, "imm"));
	}

	@Test
	void fromErrorCodeUnknownYieldsBaseExceptionPreservingCode() {
		KadException e = KadException.fromErrorCode(201, "generic");
		assertEquals(KadException.class, e.getClass());
		assertEquals(201, e.getCode());

		KadException e2 = KadException.fromErrorCode(99999, "weird");
		assertEquals(KadException.class, e2.getClass());
		assertEquals(99999, e2.getCode());
	}

	@Test
	void errorBodyGetCauseProducesTypedException() {
		var err = new io.bosonnetwork.kademlia.protocol.Error(400, "bad token");
		assertInstanceOf(InvalidToken.class, err.getCause());
		assertEquals(400, err.getCause().getCode());
	}

	@Test
	void protocolErrorAlwaysReportsProtocolErrorCode() {
		// All ProtocolError constructors must report code 203, not InvalidPeer (402).
		int expected = ErrorCode.ProtocolError.value();
		assertEquals(expected, new ProtocolError().getCode());
		assertEquals(expected, new ProtocolError("boom").getCode());
		assertEquals(expected, new ProtocolError("boom", new RuntimeException()).getCode());
		assertEquals(expected, new ProtocolError(new RuntimeException()).getCode());
	}
}
