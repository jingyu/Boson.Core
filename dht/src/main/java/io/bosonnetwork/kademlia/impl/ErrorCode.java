/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

/**
 * @hidden
 */
public enum ErrorCode {
	Success(0),

	// internal errors
	IOError(1),
	CryptoError(2),
	ValueNotExists(3),
	NotValueOwner(4),
	ValueNoRecipient(5),


	// Standard errors
	GenericError(201),
	ServerError(202),
	ProtocolError(203), //such as a malformed packet, invalid arguments, or bad token
	MethodUnknown(204),
	MessageTooBig(205),
	InvalidSignature(206),
	SaltTooBig(207),
	CasFail(301),
	SequenceNotMonotonic(302),
	ImmutableSubstitutionFail(303),

	InvalidToken(400),
	InvalidValue(401),
	InvalidPeer(402),


	Unknown(-1);

	private static final ErrorCode[] VALUES = values();

	private final int value;

	private ErrorCode(int value) {
		this.value = value;
	}

	public int value() {
		return value;
	}

	/**
	 * Returns the {@code ErrorCode} for the given numeric code, or {@link #Unknown}
	 * if no constant matches. Derived from the declared constants so that newly added
	 * codes (e.g. the {@code 400}-{@code 402} validation errors) are mapped automatically.
	 *
	 * @param code the numeric error code.
	 * @return the matching {@code ErrorCode}, or {@link #Unknown} if none matches.
	 */
	public static ErrorCode valueOf(int code) {
		for (ErrorCode ec : VALUES) {
			if (ec.value == code)
				return ec;
		}

		return Unknown;
	}
}