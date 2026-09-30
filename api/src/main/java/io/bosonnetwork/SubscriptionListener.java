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

package io.bosonnetwork;

/**
 * Receives what a {@link Subscription} delivers.
 * <p>
 * Called on the node's own context, one call at a time: an implementation must not block, and should hand
 * slow work to its own executor.
 * </p>
 *
 * @param <T> {@link Value} for a value subscription, {@link PeerInfo} for a peer subscription.
 */
@FunctionalInterface
public interface SubscriptionListener<T> {
	/**
	 * Called with a record newer than any delivered before: a value whose sequence number rose, or a peer
	 * record whose fingerprint is new or whose sequence number rose. The record has been validated.
	 *
	 * @param record the record.
	 */
	void onUpdate(T record);

	/**
	 * Called when the number of storing nodes watching for this node changes.
	 *
	 * @param subscribedNodes the number, across address families; 0 means updates will be late until a
	 *                        node is watching again.
	 */
	default void onStatusChange(int subscribedNodes) {
	}
}
