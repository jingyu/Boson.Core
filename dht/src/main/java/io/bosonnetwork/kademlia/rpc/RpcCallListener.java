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

package io.bosonnetwork.kademlia.rpc;

/**
 * Defines a listener interface for handling events in the lifecycle of an {@link RpcCall}
 * within a Kademlia Distributed Hash Table (DHT) system. Implementations of this interface
 * receive notifications when an RPC call changes state, receives a response, stalls, or times out.
 * This interface is designed for internal use within the DHT system and operates in a single-threaded
 * environment, ensuring thread safety without synchronization. All methods provide default empty
 * implementations, allowing implementers to override only the events of interest.
 *
 * <p>Listeners are notified in the following order during an {@link RpcCall} lifecycle:
 * <ol>
 *   <li>{@link #onStateChange(RpcCall, RpcCall.State, RpcCall.State)} for any state transition.</li>
 *   <li>Specific event methods ({@link #onResponse(RpcCall)}, {@link #onStall(RpcCall)},
 *       {@link #onTimeout(RpcCall)}) for corresponding states, if applicable.</li>
 * </ol>
 *
 * @see RpcCall
 * @see RpcServer
 */
@FunctionalInterface
public interface RpcCallListener {
	/**
	 * Called when the state of an {@link RpcCall} changes.
	 * This method is invoked for every state transition, including to final states
	 * ({@link RpcCall.State#TIMEOUT}, {@link RpcCall.State#CANCELED},
	 * {@link RpcCall.State#ERROR}, {@link RpcCall.State#RESPONDED}).
	 *
	 * @param call     the RPC call whose state changed
	 * @param previous the previous state of the call
	 * @param state    the current state of the call
	 */
	void onStateChange(RpcCall call, RpcCall.State previous, RpcCall.State state);

	/**
	 * Called when a valid response is received for an {@link RpcCall}, corresponding to
	 * the {@link RpcCall.State#RESPONDED} state. This method is invoked after
	 * {@link #onStateChange(RpcCall, RpcCall.State, RpcCall.State)}.
	 *
	 * @param call the RPC call that received a response
	 */
	public default void onResponse(RpcCall call) {}

	/**
	 * Called when an {@link RpcCall} is estimated to be unlikely to succeed but has not yet
	 * timed out, corresponding to the {@link RpcCall.State#STALLED} state. This method is
	 * invoked after {@link #onStateChange(RpcCall, RpcCall.State, RpcCall.State)} and may
	 * be followed by a re-scheduled timeout or a transition to another state.
	 *
	 * @param call the RPC call that has stalled
	 */
	public default void onStall(RpcCall call) {}

	/**
	 * Called when an {@link RpcCall} times out without receiving a response, corresponding
	 * to the {@link RpcCall.State#TIMEOUT} state. This method is invoked after
	 * {@link #onStateChange(RpcCall, RpcCall.State, RpcCall.State)} and after the
	 * {@link RpcServer}'s timeout handler, ensuring server notifications take precedence.
	 *
	 * @param call the RPC call that timed out
	 */
	public default void onTimeout(RpcCall call) {}
}