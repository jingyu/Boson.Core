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

package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;

/**
 * Which answers this side of a value lookup is willing to accept - and, just as much, which ones it must
 * not turn into an accusation.
 * <p>
 * {@code update} returning false is no longer only a dropped response: {@code ValueLookupTask} reports it
 * to the suspicious node detector as proven misbehavior, the one tier that can earn a full ban. So every
 * rejection here has to name something the responder chose to do, and the rule it is judged against has to
 * be the same rule {@code DHT.onFindValue} answers by.
 * </p>
 */
class EligibleValueTests {
	private static Value immutable() {
		return Value.immutableBuilder().data("the same bytes forever".getBytes()).build();
	}

	private static Value signed(int sequenceNumber) {
		return Value.signedBuilder().sequenceNumber(sequenceNumber).data("a version of something".getBytes()).build();
	}

	@Test
	void testAnImmutableValueIsExemptFromTheSequenceCheck() {
		// The case that made this worth a test. An immutable value has no publisher key and so no version
		// history to select between; its sequence number is fixed at zero by construction. onFindValue
		// exempts it and serves it whatever was asked for, so a node answering this way is obeying the
		// protocol - and reading the rule any other way here would charge every one of the k closest nodes
		// with misbehavior for it. The caller cannot avoid asking: whether an id is mutable is what the
		// lookup is for.
		Value value = immutable();
		EligibleValue eligible = new EligibleValue(value.getId(), 7);

		assertTrue(eligible.update(value), "an immutable value was rejected for a sequence number it cannot have");
		assertSame(value, eligible.getValue());
	}

	@Test
	void testAMutableValueBelowTheExpectedSequenceIsStillRejected() {
		// The other half, and the reason the exemption is narrow: onFindValue does apply the check to a
		// mutable value, so a node that answers with an older one really has broken the rule.
		Value value = signed(3);
		EligibleValue eligible = new EligibleValue(value.getId(), 4);

		assertFalse(eligible.update(value));
		assertTrue(eligible.isEmpty());
	}

	@Test
	void testAMutableValueAtOrAboveTheExpectedSequenceIsAccepted() {
		Value value = signed(4);
		EligibleValue eligible = new EligibleValue(value.getId(), 4);

		assertTrue(eligible.update(value));
		assertSame(value, eligible.getValue());
	}

	@Test
	void testAValueForADifferentTargetIsRejected() {
		// Unchanged by the exemption, and it has to be: this one is a genuine violation whatever the value
		// is made of, so an immutable value must not ride the exemption past it.
		EligibleValue eligible = new EligibleValue(Id.random(), -1);

		assertFalse(eligible.update(immutable()));
		assertTrue(eligible.isEmpty());
	}

	@Test
	void testTheSequenceCheckIsOffAltogetherWhenUnspecified() {
		Value value = signed(0);
		EligibleValue eligible = new EligibleValue(value.getId(), -1);

		assertTrue(eligible.update(value));
	}
}
