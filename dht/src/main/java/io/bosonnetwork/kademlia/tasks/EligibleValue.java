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

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;

/**
 * This class tracks a single value eligible for a Kademlia value lookup or store task.
 * It enforces target ID matching, minimum sequence number, and validity checks.
 * It keeps at most one value, preferring the one with the highest sequence number.
 */
public class EligibleValue {
	/**
	 * The lookup target ID this value must match.
	 */
	private final Id target;

	/**
	 * The minimum acceptable sequence number; note that a negative value disables the check.
	 */
	private final int expectedSequenceNumber;

	/**
	 * The currently selected eligible value, or null if none has been accepted yet.
	 */
	private Value value;

	/**
	 * Constructs an EligibleValue tracker for the given target ID and expected sequence number.
	 *
	 * @param target the lookup target ID this value must match
	 * @param expectedSequenceNumber the minimum acceptable sequence number; negative disables the check
	 */
	public EligibleValue(Id target, int expectedSequenceNumber) {
		this.target = target;
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.value = null;
	}

	/**
	 * Indicates whether an eligible value has been accepted.
	 *
	 * @return true if no eligible value has been accepted yet, false otherwise
	 */
	public boolean isEmpty() {
		return value == null;
	}

	/**
	 * Attempts to update the eligible value with the provided value.
	 * <p>
	 * Validation rules:
	 * <ul>
	 *   <li>The value's ID must match the target ID.</li>
	 *   <li>If expectedSequenceNumber is non-negative, the value's sequence number must be at least that number.</li>
	 *   <li>The value must be valid (as per {@link Value#isValid()}).</li>
	 * </ul>
	 * <p>
	 * Update semantics:
	 * <ul>
	 *   <li>If the provided value passes all validation checks, it will be accepted.</li>
	 *   <li>If no value has been accepted yet, the provided value is set as the current eligible value.</li>
	 *   <li>If a value is already accepted, the provided value replaces it only if its sequence number is higher.</li>
	 * </ul>
	 *
	 * @param v the value to consider for acceptance
	 * @return true if the value was accepted (either set or replaced existing), false otherwise
	 */
	public boolean update(Value v) {
		if (!v.getId().equals(target) ||
				(expectedSequenceNumber >=0 && v.getSequenceNumber() < expectedSequenceNumber) ||
				!v.isValid())
			return false;

		if (this.value == null)
			this.value = v;

		if (v.getSequenceNumber() > this.value.getSequenceNumber())
			this.value = v;

		return true;
	}

	/**
	 * Returns the current eligible value.
	 *
	 * @return the accepted eligible value, or null if none has been accepted yet
	 */
	public Value getValue() {
		return value;
	}
}