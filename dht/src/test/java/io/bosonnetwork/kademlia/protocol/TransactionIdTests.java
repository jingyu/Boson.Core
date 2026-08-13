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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Guards the properties the outgoing transaction id counter is required to have.
 * <p>
 * A response is matched to an outstanding call by transaction id and then by source address, so a
 * counter that advances by one tells anyone who has seen a single packet of ours exactly which id
 * to forge next. The random step is what makes that a search instead of arithmetic - and it is the
 * kind of thing a later reader will be tempted to simplify away, which is what these tests are for.
 * </p>
 */
public class TransactionIdTests {
	// The generator's upper bound is exclusive, so nextInt(1, 512) yields at most 511.
	private static final int MAX_STEP = 511;

	private static long nextTxid() {
		return Message.pingRequest().getTxid();
	}

	@AfterAll
	static void restoreTxidBase() {
		// The counter is static and shared with every other test in this JVM. Nothing depends on its
		// absolute value, but leave it somewhere ordinary rather than wherever the zero-crossing test
		// happened to stop.
		Message.setTxidBase(1);
	}

	@Test
	public void testTheCounterAdvancesByAtLeastOneAndAtMostTheStepBound() {
		long previous = nextTxid();

		for (int i = 0; i < 1000; i++) {
			long txid = nextTxid();
			// int arithmetic wraps the same way the counter does, so this stays correct across the
			// point where the unsigned value rolls over.
			int step = (int) txid - (int) previous;
			assertTrue(step >= 1 && step <= MAX_STEP,
					"step out of range: " + previous + " -> " + txid + " (" + step + ")");
			previous = txid;
		}
	}

	@Test
	public void testTheStepIsNotConstant() {
		// The whole point of the change: a fixed step of one - or any fixed step - is predictable from a
		// single observed packet. Reverting nextTxid to getAndIncrement fails here.
		long previous = nextTxid();
		long second = nextTxid();
		int first = (int) second - (int) previous;
		previous = second;

		boolean varied = false;
		for (int i = 0; i < 1000 && !varied; i++) {
			long txid = nextTxid();
			varied = ((int) txid - (int) previous) != first;
			previous = txid;
		}

		assertTrue(varied, "every step was " + first + "; the counter is predictable");
	}

	@Test
	public void testTheCounterNeverYieldsZero() {
		// Zero is an absent transaction id on the wire - Message's deserializer rejects it - so a message
		// carrying one is dropped by the peer and the call dies of a timeout instead of being answered.
		// The counter can only land on zero as it wraps, which no realistic run of requests would reach,
		// so the crossing is staged: seat the counter just below zero and take one step over it. Repeated
		// enough times that a missing guard is caught with near-certainty rather than by luck.
		for (int repeat = 0; repeat < 20; repeat++) {
			for (int base = -MAX_STEP; base < 0; base++) {
				Message.setTxidBase(base);
				long txid = nextTxid();
				assertNotEquals(0L, txid, "a zero transaction id is not a valid message");
				assertTrue(txid > 0 && txid <= 0xFFFFFFFFL, "transaction id out of unsigned range: " + txid);
			}
		}
	}
}
