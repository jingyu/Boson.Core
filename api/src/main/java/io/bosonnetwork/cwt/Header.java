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
 * Enumeration of standard headers used in the protected and unprotected
 * header maps of a COSE structure (RFC 8152).
 */
public enum Header {
	/**
	 * Cryptographic algorithm to use
	 */
	ALG(1),
	/**
	 * Critical headers to be understood
	 */
	CRIT(2),
	/**
	 * Content type of the payload
	 */
	CONTENT_TYPE(3),
	/**
	 * Key identifier
	 */
	KID(4),
	/**
	 * Full Initialization Vector
	 */
	IV(5),
	/**
	 * Partial Initialization Vector
	 */
	PARTIAL_IV(6),
	/**
	 * CBOR-encoded signature structure
	 */
	COUNTER_SIGNATURE(7);

	private final int value;

	Header(int value) {
		this.value = value;
	}

	/**
	 * Returns the integer value representing the header key.
	 *
	 * @return the header key value.
	 */
	public int getValue() {
		return value;
	}

	/**
	 * Retrieves the Header enumeration corresponding to the given integer value.
	 *
	 * @param value the integer value of the header.
	 * @return the corresponding Header enumeration.
	 * @throws IllegalArgumentException if the value is not a known header.
	 */
	public static Header valueOf(int value) {
		return switch (value) {
			case 1 -> ALG;
			case 2 -> CRIT;
			case 3 -> CONTENT_TYPE;
			case 4 -> KID;
			case 5 -> IV;
			case 6 -> PARTIAL_IV;
			case 7 -> COUNTER_SIGNATURE;
			default -> throw new IllegalArgumentException("Unknown HEADER value: " + value);
		};
	}
}