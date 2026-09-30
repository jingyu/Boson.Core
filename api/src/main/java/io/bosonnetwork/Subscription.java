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

import java.util.concurrent.CompletableFuture;

/**
 * A live subscription to a value or to the peer records under an id, made with
 * {@link Node#subscribeValue(Id, int, SubscriptionListener)} or
 * {@link Node#subscribePeer(Id, SubscriptionListener)}.
 * <p>
 * It stays active until cancelled or until the node stops. The node keeps it alive on the network by
 * itself - renewing it, and moving it to other nodes when the ones holding it fail or are no longer the
 * closest to the id.
 * </p>
 */
public interface Subscription {
	/**
	 * Returns the id this subscription watches.
	 *
	 * @return the value id or peer id.
	 */
	Id getTarget();

	/**
	 * Returns how many storing nodes currently confirm they are watching for this node, across address
	 * families.
	 *
	 * @return the number; 0 means no node is watching at the moment, and updates will be late until one is.
	 */
	int getSubscribedNodes();

	/**
	 * Returns whether this subscription has been cancelled.
	 *
	 * @return true once {@link #cancel()} has been called or the node has stopped.
	 */
	boolean isCancelled();

	/**
	 * Cancels the subscription. No update is delivered after the returned future completes.
	 *
	 * @return a {@link CompletableFuture} completing once the subscription is cancelled locally; telling the
	 *         storing nodes is best effort, and their leases run out anyway.
	 */
	CompletableFuture<Void> cancel();
}
