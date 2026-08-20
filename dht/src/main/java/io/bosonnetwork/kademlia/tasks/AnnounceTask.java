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

package io.bosonnetwork.kademlia.tasks;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.impl.ErrorCode;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;

/**
 * Base for the tasks that publish something to the closest nodes a lookup found - a value via
 * {@code STORE_VALUE}, a peer via {@code ANNOUNCE_PEER}.
 * <p>
 * The two differ only in what they send. What they share, and what lives here, is the accounting: which of
 * the nodes accepted the write, which refused it and why, and whether the attempt is worth continuing. That
 * accounting is the whole point of the class - a publish that reached nobody used to be indistinguishable
 * from one that reached everybody, because neither task looked at a single reply.
 * </p>
 * <p>
 * Designed for single-threaded use in a Vert.x event loop; not thread-safe.
 * </p>
 *
 * @param <S> the concrete task type, enabling method chaining.
 */
public abstract class AnnounceTask<S extends AnnounceTask<S>> extends Task<S> {
	/** Queue of nodes still to be written to. */
	private final Deque<CandidateNode> todo;
	/** The expected sequence number for the payload; -1 disables the check. */
	protected final int expectedSequenceNumber;

	/**
	 * What each node answered, in the order the nodes were considered.
	 * <p>
	 * Keyed on the id from the call rather than on the {@link CandidateNode}, because {@code
	 * Task.sendCall} replaces a dual-stack candidate with a plain {@code NodeInfo} when it narrows to one
	 * address family, so the object that comes back is not always the one that went out.
	 * </p>
	 */
	private final Map<Id, AnnounceResult.Target> outcomes;

	/**
	 * Constructs an announce task.
	 *
	 * @param context                the Kademlia context, must not be null.
	 * @param expectedSequenceNumber the expected sequence number for the payload; -1 to disable.
	 */
	protected AnnounceTask(KadContext context, int expectedSequenceNumber) {
		super(context);
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.todo = new ArrayDeque<>();
		this.outcomes = new LinkedHashMap<>();
	}

	/**
	 * Sets the nodes to publish to, normally the closest set of a {@link NodeLookupTask} run with
	 * {@code setWantToken(true)}.
	 *
	 * @param closest the set of closest nodes.
	 * @return this task for method chaining.
	 */
	@SuppressWarnings("unchecked")
	public S closest(ClosestSet closest) {
		todo.addAll(closest.entries());
		getLogger().debug("{}#{} added {} nodes to announce queue", getName(), getId(), closest.entries().size());
		return (S) this;
	}

	/**
	 * Builds the request to send to one node.
	 *
	 * @param cn the node to write to, which has already been checked for a token.
	 * @return the request message.
	 */
	protected abstract Message createRequest(CandidateNode cn);

	/**
	 * The RPC name, for log lines.
	 *
	 * @return the method name.
	 */
	protected abstract String getMethodName();

	/**
	 * Sends the write to as many queued nodes as the concurrency limit allows.
	 */
	@Override
	protected void iterate() {
		getLogger().trace("{}#{} todo.size={}", getName(), getId(), todo.size());
		while (!todo.isEmpty() && canDoRequest()) {
			CandidateNode cn = todo.peekFirst();
			if (cn == null) {
				// Unreachable while todo is an ArrayDeque, which returns null from peekFirst only when
				// empty. Kept as a guard, but the queue has to shrink before continuing or this spins.
				getLogger().warn("{}#{} unexpected null candidate in non-empty queue", getName(), getId());
				todo.removeFirst();
				continue;
			}

			if (!cn.hasToken()) {
				getLogger().warn("{}#{} skipping candidate {} due to missing token", getName(), getId(), cn.getId());
				record(cn.getId(), AnnounceResult.Outcome.NOT_SENT, null);
				todo.remove(cn);
				continue;
			}

			getLogger().debug("{}#{} sending {} RPC to {}", getName(), getId(), getMethodName(), cn.getId());
			// The removal is repeated in the failure handler rather than left to the callback above,
			// because a callback that threw is logged and the send goes ahead anyway - this is the one
			// place that can still take the node off the queue.
			sendCall(cn, createRequest(cn), c -> todo.remove(cn), (c, e) -> {
				recordNotSent(cn.getId(), e);
				todo.remove(cn);
			});
		}
	}

	/**
	 * Records one node's answer.
	 * <p>
	 * First answer wins. A call reaches a terminal state once, so a second entry for the same node would
	 * mean the state machine delivered twice, and overwriting would quietly hide it.
	 * </p>
	 *
	 * @param nodeId  the node that answered.
	 * @param outcome how it answered.
	 * @param cause   what it claimed, for a refusal; null otherwise.
	 */
	private void record(Id nodeId, AnnounceResult.Outcome outcome, Throwable cause) {
		outcomes.putIfAbsent(nodeId, new AnnounceResult.Target(nodeId, outcome, causeOf(cause)));
	}

	/**
	 * Restates a failure as the code and message a caller can carry off this node.
	 * <p>
	 * A refusal was already a code and a message when it arrived - the wire carries
	 * {@code Error(c, m)}, and {@link KadException#fromErrorCode} is what turned it into an exception
	 * on the way in. Taking the code back off it is not a loss of detail; it is the detail, with a
	 * detour removed that only ever existed for Java's benefit.
	 * </p>
	 * <p>
	 * Failures that never left this node are described with the same vocabulary rather than a private
	 * one, because there is nothing to disambiguate: the {@link AnnounceResult.Outcome} already says
	 * whether a peer refused or the send failed here, so a code means "what went wrong" in both cases
	 * and never has to also mean "who said so". {@code ServerError} is the fallback because every
	 * remaining case - the DHT stopped, the call rejected by our own budget, a bug - is an internal
	 * failure of this node, which is the server as far as anyone reading the result is concerned.
	 * </p>
	 *
	 * @param cause the failure, or null if there is nothing to say.
	 * @return the cause to report, or null.
	 */
	private static AnnounceResult.Cause causeOf(Throwable cause) {
		if (cause == null)
			return null;

		int code;
		if (cause instanceof KadException ke)
			code = ke.getCode();
		else if (cause instanceof CryptoException)
			code = ErrorCode.CryptoError.value();
		else if (cause instanceof IOException)
			code = ErrorCode.IOError.value();
		else
			code = ErrorCode.ServerError.value();

		return new AnnounceResult.Cause(code, cause.getMessage());
	}

	/**
	 * Records a node the write never reached, overriding whatever the call state machine concluded.
	 * <p>
	 * The one case where two reports for one node are legitimate rather than a bug, so the one case that
	 * overwrites. A send that reaches the socket and fails there also fails the call, and
	 * {@link #callError} sees that first and calls it a refusal - but the node refused nothing, it was
	 * never asked, and telling those apart is what a per-target result is for. This report is the one with
	 * the information: it knows the send is what failed. Nothing can arrive after it either, since a call
	 * that was never sent has no answer coming.
	 * </p>
	 *
	 * @param nodeId the node the write could not be sent to.
	 * @param cause  why the send failed.
	 */
	private void recordNotSent(Id nodeId, Throwable cause) {
		outcomes.put(nodeId, new AnnounceResult.Target(nodeId, AnnounceResult.Outcome.NOT_SENT, causeOf(cause)));
	}

	/**
	 * Records a node whose write was cancelled before it could be answered.
	 * <p>
	 * Not sent, in the sense the caller cares about: the write may have reached the wire, but nothing
	 * acknowledged it and nothing ever will, so it cannot be reported as delivered.
	 * </p>
	 *
	 * @param call the cancelled call.
	 */
	@Override
	protected void callCanceled(RpcCall call) {
		recordNotSent(call.getTargetId(), null);
		getLogger().debug("{}#{} {} to {} was canceled", getName(), getId(), getMethodName(), call.getTargetId());
	}

	/**
	 * Records a node that accepted the write.
	 *
	 * @param call the acknowledged call.
	 */
	@Override
	protected void callResponded(RpcCall call) {
		record(call.getTargetId(), AnnounceResult.Outcome.ACKNOWLEDGED, null);
		getLogger().debug("{}#{} {} acknowledged by {}", getName(), getId(), getMethodName(), call.getTargetId());
	}

	/**
	 * Records a refusal, and carries on to the next node.
	 * <p>
	 * <b>No refusal ends the announce, whatever it says.</b> A node that answers "your sequence number is
	 * stale" or "this value is invalid" may be right, may hold corrupt state, or may be saying it to keep
	 * the payload off the network - and nothing distinguishes those. Treating any of them as authoritative
	 * would hand every one of the k closest nodes a veto over the publish, which is a cheaper attack than
	 * anything it would defend against. The answer is recorded and the remaining nodes are still asked;
	 * what a set of refusals means is the caller's to decide, with all of them in front of it.
	 * </p>
	 *
	 * @param call the failed call.
	 */
	@Override
	protected void callError(RpcCall call) {
		Throwable cause = call.getCause();
		record(call.getTargetId(), AnnounceResult.Outcome.REFUSED, cause);
		getLogger().debug("{}#{} {} refused by {}: {}", getName(), getId(), getMethodName(),
				call.getTargetId(), cause == null ? "unknown" : cause.getMessage());
	}

	/**
	 * Records a node that never answered.
	 *
	 * @param call the timed-out call.
	 */
	@Override
	protected void callTimeout(RpcCall call) {
		record(call.getTargetId(), AnnounceResult.Outcome.TIMED_OUT, null);
		getLogger().debug("{}#{} {} to {} timed out", getName(), getId(), getMethodName(), call.getTargetId());
	}

	/**
	 * What every node answered.
	 * <p>
	 * Reflects the calls that have settled, so it is only a complete account once the task has ended - a
	 * request still in flight has no answer yet and is simply absent. That is what the aggregate is read
	 * from, since {@link #isDone()} requires the in-flight set to be empty. A task cancelled with
	 * requests outstanding reports what it knew, which is the honest answer: those nodes were asked and
	 * never replied to us either way.
	 * </p>
	 *
	 * @return the result, empty if the task never ran.
	 */
	public AnnounceResult getResult() {
		return AnnounceResult.of(outcomes.values());
	}

	/**
	 * Whether at least one node accepted the write.
	 *
	 * @return true if the payload reached the network.
	 */
	public boolean isAnnounced() {
		return getResult().isAnnounced();
	}

	/**
	 * Checks if the task is complete: nothing left to send and nothing still in flight.
	 *
	 * @return true if the task is done, false otherwise.
	 */
	@Override
	protected boolean isDone() {
		return todo.isEmpty() && super.isDone();
	}

	/**
	 * Returns a detailed string representation of the task's state.
	 *
	 * @return the status string.
	 */
	@Override
	protected String getStatus() {
		StringBuilder status = new StringBuilder();

		status.append(this).append('\n');
		status.append(getResult()).append('\n');
		status.append("todo: \n");
		if (!todo.isEmpty())
			status.append(todo.stream().map(NodeInfo::toString).collect(Collectors.joining("\n    ", "    ", "\n")));
		else
			status.append("    <empty>\n");

		return status.toString();
	}
}
