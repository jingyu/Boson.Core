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

package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

/**
 * Signals that a node refused a SUBSCRIBE because one of its subscription quotas is reached. The
 * subscriber treats it like an unreachable node and subscribes elsewhere.
 */
public class SubscriptionRefusedException extends KadException {
	private static final long serialVersionUID = -2914427046116395001L;

	/**
	 * Constructs a new exception indicating that a subscription was refused.
	 * The detail message is null, and the cause is not initialized. The cause
	 * may subsequently be initialized by a call to {@link #initCause}.
	 */
	public SubscriptionRefusedException() {
		super(ErrorCode.SubscriptionRefused.value());
	}

	/**
	 * Constructs a new exception indicating that a subscription was refused.
	 * The detail message is initialized with the specified message.
	 *
	 * @param message the detail message
	 */
	public SubscriptionRefusedException(String message) {
		super(ErrorCode.SubscriptionRefused.value(), message);
	}

	/**
	 * Constructs a new exception indicating that a subscription was refused.
	 * The detail message is initialized with the specified message and the
	 * cause is initialized with the specified cause.
	 *
	 * @param message the detail message
	 * @param cause the cause
	 */
	public SubscriptionRefusedException(String message, Throwable cause) {
		super(ErrorCode.SubscriptionRefused.value(), message, cause);
	}
}
