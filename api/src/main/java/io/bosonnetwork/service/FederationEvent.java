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
 * A change in whether a node is admitted, sent to the {@link FederationContext#onChange listeners} of
 * each service: drop whatever was cached about the node and act on the new admission, for example by
 * closing its sessions when it is refused.
 *
 * @param nodeId    the node whose admission changed
 * @param admission its admission now
 */
public record FederationEvent(Id nodeId, Admission admission) {
	/**
	 * Creates an event.
	 *
	 * @param nodeId    the node whose admission changed
	 * @param admission its admission now
	 */
	public FederationEvent {
		Objects.requireNonNull(nodeId, "nodeId");
		Objects.requireNonNull(admission, "admission");
	}
}
