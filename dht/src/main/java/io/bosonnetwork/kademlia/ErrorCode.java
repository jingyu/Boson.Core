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

package io.bosonnetwork.kademlia;

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

	Unknown(-1);

	private final int value;

	private ErrorCode(int value) {
		this.value = value;
	}

	public int value() {
		return value;
	}

	public static ErrorCode valueOf(int code) {
		return switch (code) {
			case 0 -> Success;
			case 201 -> GenericError;
			case 202 -> ServerError;
			case 203 -> ProtocolError;
			case 204 -> MethodUnknown;
			case 205 -> MessageTooBig;
			case 206 -> InvalidSignature;
			case 207 -> SaltTooBig;
			case 301 -> CasFail;
			case 302 -> SequenceNotMonotonic;
			case 303 -> ImmutableSubstitutionFail;
			default -> Unknown;
		};
	}
}