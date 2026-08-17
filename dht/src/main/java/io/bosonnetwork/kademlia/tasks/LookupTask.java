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

package io.bosonnetwork.kademlia.tasks;

import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.impl.KadConstants;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.FindNodeResponse;
import io.bosonnetwork.kademlia.protocol.LookupResponse;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.utils.AddressUtils;

/**
 * Abstract base class for Kademlia lookup tasks, such as node, value, or peer lookups.
 * This class manages an iterative lookup process to find nodes or values close to a target ID,
 * using a set of closest nodes and a queue of candidates to query. It extends {@link Task}
 * to leverage its RPC management and lifecycle handling in a single-threaded Vert.x event loop.
 *
 * @param <R> the result type of the lookup (e.g., node list, value, peers)
 * @param <S> the specific task type, enabling method chaining
 */
public abstract class LookupTask<R, S extends LookupTask<R, S>> extends Task<S> {
	/**
	 * The iteration budget for this lookup: a backstop, not the termination rule.
	 * <p>
	 * <b>What it controls.</b> How many times {@link #iterate()} may run before the lookup gives up
	 * and reports what it has. Iterations are driven by RPC state changes, so this is effectively a
	 * ceiling on the RPCs one lookup may spend.
	 * </p>
	 * <p>
	 * <b>Why it is derived, not configured.</b> The lookup normally ends by convergence - see
	 * {@link #isDone()} - and convergence has a minimum cost: {@code k} responses to fill the closest
	 * set, plus {@link ClosestSet#stabilityMargin(int)} + 1 more that fail to improve its tail. The
	 * floor is read from {@code ClosestSet} rather than restated here, so the two cannot drift apart.
	 * A budget below it would stop every lookup by exhaustion instead, and the caller could not tell,
	 * because both outcomes report COMPLETED. Exposing this as a free-standing configuration knob would
	 * let an operator set it below the floor and quietly break every lookup on the node.
	 * </p>
	 * <p>
	 * <b>An iteration is not an RPC, and that is what sizes the slack.</b> {@code Task.tryIterate} runs
	 * on every call state change at or past {@code STALLED}, and a call stalls as soon as it outlives
	 * the timeout sampler's estimate - a percentile, so a share of perfectly healthy calls stall by
	 * construction. A fast response therefore costs one iteration, a slow one costs two, and a lost one
	 * costs two plus the retry it re-queues. The budget over-counts RPCs by up to a factor of two
	 * exactly when the network is bad, which is why the slack is a flat
	 * {@code alpha * LOOKUP_DEPTH_ALLOWANCE} rather than something tighter: at the default k it leaves
	 * about 24 iterations above a 25-response floor.
	 * </p>
	 * <p>
	 * <b>Behavior as k grows.</b> The floor grows with k, but no longer at twice the rate - the
	 * stability margin is capped, so the floor is {@code k + min(k, 8) + 1}. The slack does not grow
	 * with k at all: convergence is O(log_k N), so a larger k needs fewer rounds, not more.
	 * </p>
	 * <p>
	 * Implementation limit: invisible to peers, bounding only this node's own effort.
	 * </p>
	 */
	protected final int maxIterations;

	/** The target ID for the lookup. */
	private final Id target;
	/** Set of closest nodes to the target, limited to the configured bucket size (k). */
	private final ClosestSet closest;
	/** Queue of candidate nodes to query, prioritized by distance to the target. */
	private final ClosestCandidates candidates;

	/** Current iteration count. */
	private int iterationCount = 0;

	/** The result of the lookup, set by subclasses. */
	protected R result;
	/** Indicates whether the lookup task should be considered complete when an eligible result is found. */
	protected boolean doneOnEligibleResult;
	/** Flag indicating if the lookup is complete (e.g., value found). */
	protected boolean lookupDone = false;

	/**
	 * Constructs a new lookup task for the given target ID.
	 *
	 * @param context the Kademlia context, must not be null
	 * @param target  the target ID to look up
	 * @param doneOnEligibleResult true if the lookup is complete when a result is eligible, false continue
	 */
	protected LookupTask(KadContext context, Id target, boolean doneOnEligibleResult) {
		super(context);
		this.target = target;
		this.doneOnEligibleResult = doneOnEligibleResult;

		int k = context.getK();

		// Convergence floor plus slack; see the maxIterations field for the full rationale. The floor
		// comes from ClosestSet so the budget cannot fall below what convergence actually costs, and
		// the slack is flat in k because the depth ramp shrinks as k grows.
		this.maxIterations = k + ClosestSet.stabilityMargin(k) + 1 +
				context.getAlpha() * KadConstants.LOOKUP_DEPTH_ALLOWANCE;

		this.closest = new ClosestSet(target, k);
		this.candidates = new ClosestCandidates(target, candidateCapacity(k), context.isDeveloperMode());
	}

	/**
	 * Returns how many candidates a lookup for the given bucket size will queue, and how many nodes it
	 * seeds from the local routing table.
	 * <p>
	 * {@code 3 * k}, bounded by {@link KadConstants#MAX_LOOKUP_CANDIDATES}. Both the origin of the 3
	 * and the reason for the ceiling are documented on that constant; they are not obvious from the
	 * expression and are easy to re-derive incorrectly.
	 * </p>
	 * <p>
	 * Seeding and capacity share this value on purpose: seeding more than the queue can hold would
	 * only prune the surplus on the first insertion.
	 * </p>
	 *
	 * @param k the Kademlia bucket size.
	 * @return the candidate queue capacity.
	 */
	protected static int candidateCapacity(int k) {
		return Math.min(3 * k, KadConstants.MAX_LOOKUP_CANDIDATES);
	}

	/**
	 * Returns the target ID of the lookup.
	 *
	 * @return the target ID
	 */
	public Id getTarget() {
		return target;
	}

	/**
	 * Returns the number of candidate nodes in the queue.
	 *
	 * @return the candidate count
	 */
	public int getCandidateSize() {
		return candidates.size();
	}

	/**
	 * Retrieves a candidate node by its ID.
	 *
	 * @param id the node ID
	 * @return the candidate node, or null if not found
	 */
	protected CandidateNode getCandidate(Id id) {
		return candidates.get(id);
	}

	/**
	 * Returns the candidate queue.
	 * The candidate queue stores nodes that are ordered by their XOR distance to the target ID.
	 * The queue is used to prioritize nodes for RPC queries in the {@link LookupTask}.
	 * Processed nodes remain deduplicated to prevent re-addition.
	 * Designed for single-threaded use in a Vert.x event loop; not thread-safe.
	 *
	 * @return the candidate queue
	 */
	protected ClosestCandidates getCandidates() {
		return candidates;
	}

	/**
	 * Checks if an address is eligible for inclusion in the candidate set.
	 * Accept any unicast address in developer mode; otherwise only accept global unicast address.
	 *
	 * @param addr the IP address to check
	 * @return true if the address is eligible, false otherwise
	 */
	private boolean isAddressEligible(InetAddress addr) {
		return getContext().isDeveloperMode() ? AddressUtils.isAnyUnicast(addr) : AddressUtils.isGlobalUnicast(addr);
	}

	/**
	 * Adds nodes to the candidate queue, filtering out ineligible or duplicate nodes.
	 *
	 * @param nodes the nodes to add
	 */
	protected void addCandidates(Collection<? extends NodeInfo> nodes) {
		StandardProtocolFamily family = getContext().getNetwork().protocolFamily();
		List<? extends NodeInfo> eligible = nodes.stream()
				.filter(n -> n.hasAddress(family) &&
						isAddressEligible(n.getIpAddress(family)) &&
						!getContext().isLocalId(n.getId()) &&
						!closest.contains(n.getId()))
				.toList();
		if (!eligible.isEmpty()) {
			getLogger().debug("{}#{} adding {} eligible candidates to queue", getName(), getId(), eligible.size());
			candidates.add(eligible);
		}
	}

	/**
	 * Reads the nodes a lookup response offers, taking at most as many as one answer is allowed to
	 * contribute.
	 * <p>
	 * <b>What this is for.</b> Nothing on the receive side used to bound this. The sender chose how many
	 * nodes to send and every one of them went into the candidate queue, so a single answer whose ids
	 * were all closer to the target than the incumbents evicted the entire queue - {@link
	 * ClosestCandidates} prunes to the closest {@code capacity}, and "closest" was whatever the answer
	 * said it was. One datagram was enough to decide where a lookup went.
	 * </p>
	 * <p>
	 * <b>The quota is the tighter of two ceilings.</b> {@link KadConstants#MAX_NODES_PER_RESPONSE} is
	 * what a response can carry; half the candidate queue is what one answer may claim of it. Which one
	 * binds depends on k: above k=10 the transport ceiling is already less than half the queue and the
	 * share never applies, and below it the share does - at the minimum k=4 the queue holds 12 and the
	 * transport ceiling alone would let one answer take all of them. Reserving half is what keeps an
	 * answer from displacing everything already queued.
	 * </p>
	 * <p>
	 * <b>Trimming keeps the closest, which is neutral rather than generous.</b> {@code
	 * ClosestCandidates} prunes to the closest anyway, so these are exactly the nodes that would have
	 * survived insertion. The quota changes how many of them there are, not which - which is the point:
	 * an attacker grinds ids closer than any honest node, so any rule that tried to be clever about
	 * <em>which</em> to keep would be choosing between nodes it cannot tell apart.
	 * </p>
	 * <p>
	 * <b>Why an over-ceiling response is dropped whole and its sender is not charged.</b> Dropping is a
	 * decision about our own cost, not a verdict on the sender: past the ceiling there is no point
	 * sorting a list this large to keep a handful of it, so the cheap exit comes first. It is
	 * deliberately not reported to {@link
	 * io.bosonnetwork.kademlia.security.SuspiciousNodeDetector#misbehaved}, even though the sender is
	 * provably the node we asked. That entry point is the one that can earn a full ban, and it is
	 * reserved for things no correct implementation can do - answering with the wrong method, or with a
	 * different id than the call was addressed to. A large node count is not one of them: the ceiling is
	 * this implementation's own send-side policy for staying inside a datagram, not a protocol rule, so
	 * a peer that ships a larger one is interoperating correctly and banning it would partition us from
	 * that implementation. Bounding what we accept is the right response to a number we did not choose;
	 * punishing it is not.
	 * </p>
	 * <p>
	 * <b>This does not bound one sender across a lookup.</b> It limits a single answer. A node that
	 * answers repeatedly gets the quota each time, and the nodes it supplies are queried first precisely
	 * because they are closest - so the reserved half refills from a head that sender already owns.
	 * Closing that needs per-source accounting over the whole lookup, which this is not.
	 * </p>
	 *
	 * @param response the lookup response to read.
	 * @return the nodes to offer {@link #addCandidates}, empty if there are none to take.
	 */
	protected List<NodeInfo> acceptResponse(Message response) {
		List<NodeInfo> nodes = response.<LookupResponse>getBody().getNodes(getContext().getNetwork());
		if (nodes.isEmpty()) {
			getLogger().debug("{}#{} empty node list in response from {}", getName(), getId(), response.getId());
			return List.of();
		}

		if (nodes.size() > KadConstants.MAX_NODES_PER_RESPONSE) {
			getLogger().debug("{}#{} dropping response carrying {} nodes from {}, over the {} we are willing to read",
					getName(), getId(), nodes.size(), response.getId(), KadConstants.MAX_NODES_PER_RESPONSE);
			return List.of();
		}

		int maxAccepted = Math.min(KadConstants.MAX_NODES_PER_RESPONSE, candidateCapacity(getContext().getK()) / 2);
		if (nodes.size() <= maxAccepted)
			return nodes;

		getLogger().debug("{}#{} taking the {} closest of {} nodes offered by {}",
				getName(), getId(), maxAccepted, nodes.size(), response.getId());
		return nodes.stream()
				.sorted((a, b) -> getTarget().threeWayCompare(a.getId(), b.getId()))
				.limit(maxAccepted)
				.toList();
	}

	/**
	 * Removes a candidate node from the queue by its ID.
	 *
	 * @param id the node ID
	 * @return the removed candidate node, or null if not found
	 */
	protected CandidateNode removeCandidate(Id id) {
		return candidates.remove(id);
	}

	/**
	 * Retrieves the next candidate node to query, prioritized by distance to the target.
	 *
	 * @return the next candidate node, or null if none available
	 */
	protected CandidateNode getNextCandidate() {
		return candidates.next();
	}

	/**
	 * Checks if the candidate queue is empty.
	 *
	 * @return true if no candidates remain, false otherwise
	 */
	protected boolean isCandidatesEmpty() {
		return candidates.isEmpty();
	}

	/**
	 * Adds a candidate node to the closest set.
	 *
	 * @param cn the candidate node
	 */
	protected void addClosest(CandidateNode cn) {
		closest.add(cn);
	}

	/**
	 * Returns the set of closest nodes to the target.
	 *
	 * @return the closest set
	 */
	public ClosestSet getClosestSet() {
		return closest;
	}

	/**
	 * Sets the result of the lookup.
	 *
	 * @param result the lookup result
	 */
	protected void setResult(R result) {
		this.result = result;
	}

	/**
	 * Returns the result of the lookup.
	 *
	 * @return the result, or null if not set
	 */
	public R getResult() {
		return result;
	}

	/**
	 * Performs one iteration of the lookup, sending RPCs to the closest candidates.
	 */
	@Override
	protected void iterate() {
		iterationCount++;
	}

	/**
	 * Checks if the lookup is complete, based on explicit completion, no remaining candidates,
	 * or the closest set being closer to the target than the next candidate.
	 * Uses Kademlia's three-way comparison to compare distances to the target.
	 *
	 * @return true if the lookup is done, false otherwise
	 */
	@Override
	protected boolean isDone() {
		/*/
		return lookupDone || iterationCount >= maxIterations ||
				(super.isDone() && (getCandidateSize() == 0 ||
				(closest.isEligible() && (candidates.head() == null ||
						target.threeWayCompare(closest.tail(), candidates.head()) <= 0))));
		*/
		// using the verbose version, easy to debug and trace the potential problems
		Logger log = getLogger();
		if (lookupDone) {
			log.debug("{}#{} terminating lookup: explicit completion signaled (lookupDone)", getName(), getId());
			return true;
		}
		if (iterationCount >= maxIterations) {
			log.debug("{}#{} terminating lookup: reached maximum iterations ({})", getName(), getId(), maxIterations);
			return true;
		}
		if (!super.isDone()) {
			log.trace("{}#{} lookup not done: pending RPCs remain", getName(), getId());
			return false;
		}
		if (getCandidateSize() == 0) {
			log.debug("{}#{} terminating lookup: no candidates remain", getName(), getId());
			return true;
		}
		if (closest.isEligible() && (candidates.head() == null ||
				target.threeWayCompare(closest.tail(), candidates.head()) <= 0)) {
			log.debug("{}#{} terminating lookup: closest set eligible and no closer candidates (tail={}, candidate head={})",
					getName(), getId(), closest.tail(), candidates.head());
			return true;
		}
		log.trace("{}#{} lookup not done: continuing iteration", getName(), getId());
		return false;
	}

	/**
	 * Handles an RPC error by removing the candidate node from the queue.
	 *
	 * @param call the RPC call that failed
	 */
	@Override
	protected void callError(RpcCall call) {
		NodeInfo target = call.getTarget();
		if (target instanceof CandidateNode cn) {
			getLogger().debug("{}#{} RPC error for candidate {}", getName(), getId(), cn.getId());
			candidates.remove(cn.getId());
		} else {
			candidates.remove(target.getId()); // fail-safe
			//noinspection LoggingSimilarMessage
			getLogger().warn("{}#{} unexpected target type for call: {}", getName(), getId(), target);
		}
	}

	/**
	 * Handles an RPC timeout, removing the candidate if unreachable or clearing it for retry.
	 *
	 * @param call the RPC call that timed out
	 */
	@Override
	protected void callTimeout(RpcCall call) {
		NodeInfo target = call.getTarget();
		if (target instanceof CandidateNode cn) {
			if (cn.isUnreachable()) {
				getLogger().debug("{}#{} removing unreachable candidate {}", getName(), getId(), cn.getId());
				candidates.remove(cn.getId());
			} else {
				getLogger().debug("{}#{} candidate {} timeout, mark it as unsent to retry in next iteration",
						getName(), getId(), cn.getId());
				cn.clearSent();
			}
		} else {
			candidates.remove(target.getId()); // fail-safe
			//noinspection LoggingSimilarMessage
			getLogger().warn("{}#{} unexpected target type for call: {}", getName(), getId(), target);
		}
	}

	/**
	 * Handles an RPC response, marking the candidate as replied and adding it to the closest set.
	 * Assumes the RPC server provides a valid response.
	 *
	 * @param call the RPC call with a response
	 */
	@Override
	protected void callResponded(RpcCall call) {
		CandidateNode cn = removeCandidate(call.getTargetId());
		if (cn != null) {
			cn.setReplied();
			Message response = call.getResponse();
			getLogger().debug("{}#{} received response for candidate {}, add it to closest", getName(), getId(), cn.getId());
			if (response.getBody() instanceof FindNodeResponse fnr)
				cn.setToken(fnr.getToken());
			addClosest(cn);
		}
	}

	/**
	 * Returns a detailed string representation of the task's state, including the closest nodes and candidates.
	 *
	 * @return the status string
	 */
	@Override
	protected String getStatus() {
		StringBuilder status = new StringBuilder();

		status.append(this).append('\n');
		status.append("Closest: \n");
		if (!closest.isEmpty())
			status.append(closest.stream().map(NodeInfo::toString).collect(Collectors.joining("\n    ", "    ", "\n")));
		else
			status.append("    <empty>\n");
		status.append("Candidates: \n");
		if (!candidates.isEmpty())
			status.append(candidates.entries().map(NodeInfo::toString).collect(Collectors.joining("\n    ", "    ", "\n")));
		else
			status.append("    <empty>\n");

		return status.toString();
	}
}
