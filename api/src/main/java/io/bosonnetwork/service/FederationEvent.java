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


package io.bosonnetwork.service;

import java.util.Objects;

import io.bosonnetwork.Id;

/**
 * A change about a node, sent to the {@link FederationContext#onChange listeners} of each service: its
 * admission, its relationship with this node, or what this node has learned of it. Drop whatever was
 * cached about the node, for example its sessions.
 * <p>
 * The admission it carries is this node's admission of the other one, as
 * {@link FederationContext#admit(io.bosonnetwork.Id)} gives it. It doesn't say whether traffic to the
 * node may go now - the other node may not take it yet - so a service that has something to send
 * {@linkplain FederationContext#resolve(io.bosonnetwork.Id) resolves} the peer again, and acts on that.
 *
 * @param nodeId    the node whose admission changed
 * @param admission its admission now
 */
public record FederationEvent(Id nodeId, Admission admission) {
	/**
	 * Creates an event.
	 *
	 * @param nodeId    the node whose admission changed
	 * @param admission this node's admission of it now
	 */
	public FederationEvent {
		Objects.requireNonNull(nodeId, "nodeId");
		Objects.requireNonNull(admission, "admission");
	}
}
