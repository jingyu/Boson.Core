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
 * Enumeration of standard and custom claims used in a CBOR Web Token (CWT).
 * These correspond to the integer claim keys defined in RFC 8392 and related IANA registries.
 */
public enum Claim {
	/**
	 * non-standard (Application defined) Claim
	 */
	APPLICATION_DEFINED(0),

	// RFC 8392 Claims
	/**
	 * iss (Issuer) Claim
	 */
	ISSUER(1),
	/**
	 * sub (Subject) Claim
	 */
	SUBJECT(2),
	/**
	 * aud (Audience) Claim
	 */
	AUDIENCE(3),
	/**
	 * exp (Expiration Time) Claim
	 */
	EXPIRATION(4),
	/**
	 * nbf (Not Before) Claim
	 */
	NOT_BEFORE(5),
	/**
	 * iat (Issued At) Claim
	 */
	ISSUED_AT(6),
	/**
	 * cti (CWT ID) Claim。
	 */
	CWT_ID(7),

	// IANA Extended Claims
	/**
	 * Confirmation
	 */
	CONFIRMATION(8),
	/**
	 * Scope Values
	 */
	SCOPE(9),
	/**
	 * Nonce
	 */
	NONCE(10),
	// Boson defined (IANA unassigned range 12-27)
	// https://www.iana.org/assignments/cwt/cwt.xhtml
	/**
	 * Client identifier
	 */
	CLIENT_ID(12);

	private final int value;

	Claim(int value) {
		this.value = value;
	}

	/**
	 * Returns the integer value representing the claim key.
	 *
	 * @return the claim key value.
	 */
	public int getValue() {
		return value;
	}

	/**
	 * Retrieves the Claim enumeration corresponding to the given integer value.
	 *
	 * @param value the integer value of the claim.
	 * @return the corresponding Claim enumeration.
	 */
	public static Claim valueOf(int value) {
		return switch (value) {
			case 1 -> ISSUER;
			case 2 -> SUBJECT;
			case 3 -> AUDIENCE;
			case 4 -> EXPIRATION;
			case 5 -> NOT_BEFORE;
			case 6 -> ISSUED_AT;
			case 7 -> CWT_ID;
			case 8 -> CONFIRMATION;
			case 9 -> SCOPE;
			case 10 -> NONCE;
			case 12 -> CLIENT_ID;
			default -> APPLICATION_DEFINED;
		};
	}
}