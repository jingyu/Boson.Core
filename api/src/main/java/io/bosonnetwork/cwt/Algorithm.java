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

package io.bosonnetwork.cwt;

/**
 * Enumeration of cryptographic algorithms supported by the CWT implementation.
 * The integer values correspond to the algorithm identifiers defined in RFC 8152.
 */
public enum Algorithm {
	/**
	 * ECDSA w/ SHA-256
	 */
	ES256(-7),
	/**
	 * ECDSA w/ SHA-384
	 */
	ES384(-35),
	/**
	 * ECDSA w/ SHA-512
	 */
	ES512(-36),
	/**
	 * EdDSA / Ed25519
	 */
	EDDSA(-8);

	private final int value;

	Algorithm(int value) {
		this.value = value;
	}

	/**
	 * Returns the integer value representing the algorithm identifier.
	 *
	 * @return the algorithm identifier value.
	 */
	public int getValue() {
		return value;
	}

	/**
	 * Retrieves the Algorithm enumeration corresponding to the given integer value.
	 *
	 * @param value the integer value of the algorithm identifier.
	 * @return the corresponding Algorithm enumeration.
	 * @throws IllegalArgumentException if the value is not a known algorithm.
	 */
	public static Algorithm valueOf(int value) {
		return switch (value) {
			case -7 -> Algorithm.ES256;
			case -8 -> Algorithm.EDDSA;
			case -35 -> Algorithm.ES384;
			case -36 -> Algorithm.ES512;
			default -> throw new IllegalArgumentException("Unknown ALGORITHM value: " + value);
		};
	}
}