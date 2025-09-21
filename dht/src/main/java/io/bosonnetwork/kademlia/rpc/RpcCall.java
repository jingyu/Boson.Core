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

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.exceptions.ProtocolError;
import io.bosonnetwork.kademlia.protocol.Error;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.utils.Timer;

/**
 * Represents an RPC call in a Kademlia Distributed Hash Table (DHT) system.
 * Manages the lifecycle of an RPC call, including sending requests, handling responses,
 * tracking timeouts, and notifying listeners of state changes. This class is designed
 * for internal use within the RpcServer and operates in a single-threaded environment.
 * It is not thread-safe and does not support serialization.
 */
public class RpcCall {
	/** The target node to which the RPC call is sent. */
	private final NodeInfo target;

	/** The request message sent to the target node. */
	private final Message<?> request;

	/** The response message received from the target node, null until received. */
	private Message<?> response;

	/** The cause of an error if the RPC call fails, null otherwise. */
	private Throwable cause;

	/** Indicates whether the target was reachable at creation time, based on KBucketEntry. */
	private boolean targetIsReachable;

	/** Timestamp when the request was sent, in milliseconds, or -1 if not sent. */
	private long sentTime = -1;

	/** Timestamp when the response was received, in milliseconds, or -1 if not received. */
	private long responseTime = -1;

	/** Expected round-trip time (RTT) for the RPC call, in milliseconds, or -1 if not set. */
	private long expectedRTT = -1;

	/** Current state of the RPC call, initialized to UNSENT. */
	private State state = State.UNSENT;

	/** Listener for state changes and events, null if no listeners are attached. */
	private RpcCallListener listener;

	/** Timer for scheduling timeouts, set by RpcServer. */
	private Timer timer;

	/** Identifier for the timeout timer, or -1 if not scheduled. */
	private long timeoutTimer = -1;

	/** Handler for timeout events, used internally by RpcServer. */
	private Consumer<RpcCall> timeoutHandler;

	/**
	 * Enumerates the possible states of an RPC call.
	 */
	public enum State {
		UNSENT,      // Call has not been sent yet
		SENT,        // Call has been sent, awaiting response
		STALLED,     // Call is delayed, possibly due to network issues
		TIMEOUT,     // Call timed out without a response
		CANCELED,    // Call was canceled before completion
		ERROR,       // Call failed due to an error
		RESPONDED;   // Call received a valid response

		public boolean isFinal() {
			return this.ordinal() >= TIMEOUT.ordinal();
		}
	}

	/**
	 * Constructs an RPC call with the specified target node and request message.
	 *
	 * @param target  the node to which the RPC call is sent
	 * @param request the request message to send
	 * @throws IllegalArgumentException if target or request is null, or if request is not of type REQUEST
	 */
	public RpcCall(NodeInfo target, Message<?> request) {
		this.target = target;
		this.request = request;
		this.listener = null;

		// Set remote ID and address on the request
		request.setRemote(target.getId(), target.getAddress());
		request.setAssociatedCall(this);

		// Initialize reachability and RTT from KBucketEntry if applicable
		if (target instanceof KBucketEntry entry) {
			targetIsReachable = entry.isReachable();
			expectedRTT = entry.getRTT();
		}
	}

	/**
	 * Sets the local node ID for the request.
	 *
	 * @param nodeId the local node ID
	 * @return this RpcCall instance for method chaining
	 */
	protected RpcCall setLocalId(Id nodeId) {
		request.setId(nodeId);
		return this;
	}

	/**
	 * Gets the ID of the target node.
	 *
	 * @return the target node's ID
	 */
	public Id getTargetId() {
		return target.getId();
	}

	/**
	 * Gets the target node information.
	 *
	 * @return the target NodeInfo
	 */
	public NodeInfo getTarget() {
		return target;
	}

	/**
	 * Checks if the target was reachable at the time of creation.
	 *
	 * @return true if the target was reachable, false otherwise
	 */
	public boolean isReachableAtCreationTime() {
		return targetIsReachable;
	}

	/**
	 * Gets the transaction ID of the request.
	 *
	 * @return the transaction ID
	 */
	public long getTxid() {
		return request.getTxid();
	}

	/**
	 * Sets the expected round-trip time (RTT) for the RPC call.
	 *
	 * @param rtt the expected RTT in milliseconds, must be positive
	 * @return this RpcCall instance for method chaining
	 */
	protected RpcCall setExpectedRtt(long rtt) {
		this.expectedRTT = rtt;
		return this;
	}

	/**
	 * Sets the expected RTT if not already set.
	 *
	 * @param rtt the expected RTT in milliseconds, must be positive
	 * @return this RpcCall instance for method chaining
	 */
	protected RpcCall setExpectedRttIfAbsent(long rtt) {
		if (expectedRTT <= 0)
			expectedRTT = rtt;
		return this;
	}

	/**
	 * Sets the expected RTT if not already set, using a supplier.
	 *
	 * @param rttSupplier supplier providing the expected RTT in milliseconds
	 * @return this RpcCall instance for method chaining
	 */
	protected RpcCall setExpectedRttIfAbsent(LongSupplier rttSupplier) {
		if (expectedRTT <= 0)
			expectedRTT = rttSupplier.getAsLong();
		return this;
	}

	/**
	 * Checks if an expected RTT has been set.
	 *
	 * @return true if expected RTT is set, false otherwise
	 */
	public boolean isSetExpectedRTT() {
		return expectedRTT > 0;
	}

	/**
	 * Gets the expected RTT for the RPC call.
	 *
	 * @return the expected RTT in milliseconds, or -1 if not set
	 */
	public long getExpectedRTT() {
		return expectedRTT;
	}

	/**
	 * Gets the request message.
	 *
	 * @param <T> the type of the request body
	 * @return the request message
	 */
	@SuppressWarnings("unchecked")
	public <T> Message<T> getRequest() {
		return (Message<T>) request;
	}

	/**
	 * Gets the response message.
	 *
	 * @param <T> the type of the response body
	 * @return the response message, or null if no response has been received
	 */
	@SuppressWarnings("unchecked")
	public <T> Message<T> getResponse() {
		return (Message<T>) response;
	}

	/**
	 * Gets the current state of the RPC call.
	 *
	 * @return the current state
	 */
	public State getState() {
		return state;
	}

	/**
	 * Checks if the RPC call is pending (i.e., not in a final state).
	 *
	 * @return true if the state is UNSENT, SENT, or STALLED, false otherwise
	 */
	public boolean isPending() {
		return state.ordinal() < State.TIMEOUT.ordinal();
	}

	/**
	 * Checks if the response ID matches the target ID.
	 *
	 * @return true if the IDs match, false otherwise
	 * @throws IllegalStateException if no response has been received
	 */
	public boolean isIdMatched() {
		if (response == null)
			throw new IllegalStateException("RPC call not responded yet");

		return response.getId().equals(target.getId());
	}

	/**
	 * Checks if the response ID does not match the target ID.
	 *
	 * @return true if the IDs do not match, false otherwise
	 * @throws IllegalStateException if no response has been received
	 */
	public boolean isIdMismatched() {
		if (response == null)
			throw new IllegalStateException("RPC call not responded yet");

		return !response.getId().equals(target.getId());
	}

	/**
	 * Checks if the response address matches the request address.
	 *
	 * @return true if the addresses match, false otherwise
	 * @throws IllegalStateException if no response has been received
	 */
	public boolean isAddressMatched() {
		if (response == null)
			throw new IllegalStateException("RPC call not responded yet");

		return response.getRemoteAddress().equals(request.getRemoteAddress());
	}

	/**
	 * Checks if the response address does not match the request address.
	 *
	 * @return true if the addresses do not match, false otherwise
	 * @throws IllegalStateException if no response has been received
	 */
	public boolean isAddressMismatched() {
		if (response == null)
			throw new IllegalStateException("RPC call not responded yet");

		return !response.getRemoteAddress().equals(request.getRemoteAddress());
	}

	/**
	 * Gets the time when the request was sent.
	 *
	 * @return the sent time in milliseconds, or -1 if not sent
	 */
	public long getSentTime() {
		return sentTime;
	}

	/**
	 * Gets the time when the response was received.
	 *
	 * @return the response time in milliseconds, or -1 if no response
	 */
	public long getResponseTime() {
		return responseTime;
	}

	/**
	 * Calculates the actual round-trip time (RTT) of the RPC call.
	 *
	 * @return the RTT in milliseconds, or -1 if not calculable
	 */
	public long getRTT() {
		if(sentTime == -1 || responseTime == -1)
			return -1;

		return responseTime - sentTime;
	}

	/**
	 * Adds a listener for state changes and events.
	 *
	 * @param listener the listener to add
	 * @return this RpcCall instance for method chaining
	 * @throws IllegalStateException if the call is not in UNSENT state
	 * @throws NullPointerException if listener is null
	 */
	public RpcCall addListener(RpcCallListener listener) {
		Objects.requireNonNull(listener, "Invalid listener");

		if(state != State.UNSENT)
			throw new IllegalStateException("Cannot attach listeners after the call is started");

		if (this.listener == null) {
			this.listener = listener;
		} else {
			if (this.listener instanceof ListenerArray listeners) {
				listeners.add(listener);
			} else {
				ListenerArray listeners = new ListenerArray();
				listeners.add(this.listener);
				listeners.add(listener);
				this.listener = listeners;
			}
		}

		return this;
	}

	/**
	 * Updates the state of the RPC call and notifies listeners.
	 *
	 * @param state the new state
	 */
	private void updateState(State state) {
		State prev = this.state;
		this.state = state;

		// Call timeout handler for TIMEOUT state first
		if (state == State.TIMEOUT && timeoutHandler != null)
			timeoutHandler.accept(this);

		if (listener == null)
			return;

		listener.onStateChange(this, prev, state);
		switch (state) {
			case TIMEOUT -> {
				// Notify RpcServer first for timeout handling
				if (timeoutHandler != null)
					timeoutHandler.accept(this);

				listener.onTimeout(this);
			}
			case STALLED -> listener.onStall(this);
			case RESPONDED -> listener.onResponse(this);
		}
	}

	/**
	 * Sets the timer for scheduling timeouts.
	 *
	 * @param timer the timer to use
	 * @return this RpcCall instance for method chaining
	 */
	protected RpcCall setTimer(Timer timer) {
		this.timer = timer;
		return this;
	}

	/**
	 * Sets the timeout handler for internal use by RpcServer.
	 *
	 * @param handler the timeout handler
	 * @return this RpcCall instance for method chaining
	 */
	RpcCall setTimeoutHandler(Consumer<RpcCall> handler) {
		this.timeoutHandler = handler;
		return this;
	}

	/**
	 * Schedules a timeout timer with the specified delay.
	 *
	 * @param delay the delay in milliseconds
	 * @throws IllegalStateException if the timer is not set
	 */
	private void setTimeoutTimer(long delay) {
		if (timer == null)
			throw new IllegalStateException("Timer not set");

		timeoutTimer = timer.setTimer(delay, this::tryTimeout);
	}

	/**
	 * Cancels the active timeout timer, if any.
	 */
	private void cancelTimeoutTimer() {
		if (timeoutTimer != -1) {
			timer.cancelTimer(timeoutTimer);
			timeoutTimer = -1;
		}
	}

	/**
	 * Handles timeout events, transitioning to STALLED or TIMEOUT state.
	 *
	 * @param unusedTimerId the timer ID (unused)
	 */
	private void tryTimeout(long unusedTimerId) {
		// clear the timeoutTimer id
		// the timer already triggered, no need to cancel
		timeoutTimer = -1;

		if (state != State.SENT && state != State.STALLED)
			return;

		long elapsed = System.currentTimeMillis() - sentTime;
		long remaining = RpcServer.RPC_CALL_TIMEOUT_MAX - elapsed;
		if (remaining > 0) {
			updateState(State.STALLED);
			// Re-schedule for final timeout
			setTimeoutTimer(remaining);
		} else {
			updateState(State.TIMEOUT);
		}
	}

	/**
	 * Marks the RPC call as sent and schedules a timeout.
	 *
	 * @throws IllegalStateException if expected RTT is not set
	 */
	protected void sent() {
		if (expectedRTT <= 0)
			throw new IllegalStateException("no expected RTT");

		sentTime = System.currentTimeMillis();
		updateState(State.SENT);
		setTimeoutTimer(expectedRTT);
	}

	/**
	 * Processes a response message, updating state and cause as needed.
	 * Validation of the response is handled by RpcServer.
	 *
	 * @param response the response message
	 * @throws NullPointerException if response is null
	 */
	protected void respond(Message<?> response) {
		responseTime = System.currentTimeMillis();
		response.setAssociatedCall(this);
		cancelTimeoutTimer();
		this.response = response;

		if (response.isError()) {
			// Extract error cause from response
			@SuppressWarnings("unchecked")
			Message<Error> error = (Message<Error>) response;
			this.cause = error.getBody().getCause();
		}

		switch(response.getType()) {
			case RESPONSE -> updateState(State.RESPONDED);
			case ERROR -> updateState(State.ERROR);
			default -> throw new IllegalStateException("INTERNAL ERROR: Invalid response type!!!");
		}
	}

	/**
	 * Handles a response received from an inconsistent socket (e.g., due to port-mangling NAT).
	 * Transitions to STALLED state to allow retry without treating as an error.
	 *
	 * @param response the response message
	 */
	protected void respondInconsistentSocket(Message<?> response) {
		// Inconsistent sockets are rare and may indicate NAT issues; stall to allow retry
		if (state != State.SENT)
			return;

		updateState(State.STALLED);
	}

	/**
	 * Handles a response with an incorrect method, treating it as a protocol error.
	 *
	 * @param response the response message
	 */
	protected void respondWrongMethod(Message<?> response) {
		// Store response and set error cause for debugging
		this.response = response;
		this.cause = new ProtocolError("Got response with wrong method");
		updateState(State.ERROR);
	}

	/**
	 * Fails the RPC call with the specified cause.
	 *
	 * @param cause the reason for the failure
	 */
	protected void fail(Throwable cause) {
		if (state.ordinal() >= State.TIMEOUT.ordinal())
			return;

		cancelTimeoutTimer();
		this.cause = cause;
		updateState(State.ERROR);
	}

	/**
	 * Cancels the RPC call, stopping further processing.
	 */
	protected void cancel() {
		if (state.ordinal() >= State.TIMEOUT.ordinal())
			return;

		cancelTimeoutTimer();
		updateState(State.CANCELED);
	}

	/**
	 * A list of listeners for RPC call events, implementing the listener interface.
	 * Extends ArrayList to support multiple listeners.
	 */
	private static class ListenerArray extends ArrayList<RpcCallListener> implements RpcCallListener {
		private static final long serialVersionUID = -434539791944886141L;

		public ListenerArray() {
			super(4);
		}

		@Override
		public void onStateChange(RpcCall call, RpcCall.State previous, RpcCall.State current) {
			for (RpcCallListener listener : this)
				listener.onStateChange(call, previous, current);
		}

		@Override
		public void onResponse(RpcCall call) {
			for (RpcCallListener listener : this)
				listener.onResponse(call);
		}

		@Override
		public void onStall(RpcCall call) {
			for (RpcCallListener listener : this)
				listener.onStall(call);
		}

		@Override
		public void onTimeout(RpcCall call) {
			for (RpcCallListener listener : this)
				listener.onTimeout(call);
		}
	}
}