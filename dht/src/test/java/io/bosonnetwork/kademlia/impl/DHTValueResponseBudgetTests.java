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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;

/**
 * Covers {@link DHT#valueFits(Value)}, the bound on the value a FIND_VALUE response carries.
 * <p>
 * The value half of what {@code DHTPeerResponseBudgetTests} covers for peers, and it exists for the
 * same reason: the length limits are enforced when a value is stored, so everything accepted since they
 * existed fits by construction - but a record written before them does not. Unlike a peer, a value has
 * no list to be dropped from, so serving it would fragment every response for that id, permanently.
 * </p>
 * <p>
 * Values are built through {@code of()} rather than a builder throughout, because that is the path the
 * storage layer and the deserializer use, and it is deliberately the one that does not enforce the
 * limits - a pre-limit record has to load and read as too large, not throw.
 * </p>
 */
public class DHTValueResponseBudgetTests {
	/** What a node stores today: each type at exactly the limit that applies to it. */
	@Test
	void aValueAtItsOwnLimitIsServed() {
		assertTrue(DHT.valueFits(Value.of(Id.random(), Random.randomBytes(Value.MAX_IMMUTABLE_DATA_BYTES))),
				"an immutable value at its limit must be servable");

		assertTrue(DHT.valueFits(mutable(Value.MAX_MUTABLE_DATA_BYTES)),
				"a mutable value at its limit must be servable");

		assertTrue(DHT.valueFits(encrypted(Value.MAX_ENCRYPTED_DATA_BYTES)),
				"an encrypted value at its limit must be servable");
	}

	/** The case the check exists for: a record that predates the limit it violates. */
	@Test
	void aValueStoredBeforeTheLimitsIsNotServed() {
		assertFalse(DHT.valueFits(Value.of(Id.random(), Random.randomBytes(Value.MAX_IMMUTABLE_DATA_BYTES + 1))));
		assertFalse(DHT.valueFits(mutable(Value.MAX_MUTABLE_DATA_BYTES + 1)));
		assertFalse(DHT.valueFits(encrypted(Value.MAX_ENCRYPTED_DATA_BYTES + 1)));
	}

	/**
	 * Which limit applies is decided by what the value carries, not by a single number, so the same
	 * length can be servable for one type and not for another. Asserted because a check written against
	 * the largest of the three would pass every test above and still emit an oversized datagram.
	 */
	@Test
	void theLimitAppliedIsTheOneForTheValuesType() {
		int length = Value.MAX_MUTABLE_DATA_BYTES + 1;

		assertTrue(DHT.valueFits(Value.of(Id.random(), Random.randomBytes(length))),
				"legal for an immutable value, which carries no owner id or signature");
		assertFalse(DHT.valueFits(mutable(length)),
				"too long for a mutable value, whose envelope is that much larger");
	}

	private static Value mutable(int dataLength) {
		return Value.of(Id.random(), 1, new byte[Signature.BYTES], Random.randomBytes(dataLength));
	}

	private static Value encrypted(int dataLength) {
		return Value.of(Id.random(), Id.random(), new byte[Value.NONCE_BYTES], 1,
				new byte[Signature.BYTES], Random.randomBytes(dataLength));
	}
}
