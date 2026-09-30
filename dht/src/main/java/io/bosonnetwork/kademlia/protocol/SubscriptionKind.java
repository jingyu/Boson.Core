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

/**
 * What a subscription watches: the value stored under an id, or the peer records announced under it.
 * <p>
 * Part of the subscription rather than implied by the target, because the two are separate namespaces
 * over the same ids - one key can own a mutable value and be a peer id at the same time.
 * </p>
 */
public enum SubscriptionKind {
	/** The value stored under the target id. */
	VALUE(1),
	/** The peer records announced under the target id. */
	PEER(2);

	private final int value;

	SubscriptionKind(int value) {
		this.value = value;
	}

	/**
	 * Returns the wire value.
	 *
	 * @return the value carried in the {@code m} field.
	 */
	public int value() {
		return value;
	}

	/**
	 * Returns the kind for a wire value.
	 *
	 * @param value the value of the {@code m} field.
	 * @return the kind.
	 * @throws IllegalArgumentException if the value names no kind.
	 */
	public static SubscriptionKind valueOf(int value) {
		return switch (value) {
			case 1 -> VALUE;
			case 2 -> PEER;
			default -> throw new IllegalArgumentException("Invalid subscription kind: " + value);
		};
	}
}
