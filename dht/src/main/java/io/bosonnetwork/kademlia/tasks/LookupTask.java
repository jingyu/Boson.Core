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
	 * {@link #isDone()} - and convergence has a minimum cost set by
	 * {@link ClosestSet#isEligible()}: about {@code 2 * k} insert attempts, k to fill the closest set
	 * and k+1 more that fail to improve its tail. A budget below that floor would stop every lookup by
	 * exhaustion instead, and the caller could not tell, because both outcomes report COMPLETED. So
	 * the budget is {@code LOOKUP_CONVERGENCE_FACTOR * k} (the floor) plus slack for the depth ramp
	 * and for iterations lost to unanswered RPCs. Exposing this as a free-standing configuration knob
	 * would let an operator set it below the floor and quietly break every lookup on the node.
	 * </p>
	 * <p>
	 * <b>Behavior as k grows.</b> The floor grows linearly with k because the convergence rule does.
	 * The slack term is {@code max(k, alpha * LOOKUP_DEPTH_ALLOWANCE)}, so for small k it is dominated
	 * by the fixed depth allowance - which is what the old {@code 3 * k} heuristic lacked, leaving
	 * roughly three unanswered RPCs of margin at k=8 before a lookup would truncate on a lossy path.
	 * For a super node at large k the depth term is irrelevant (convergence is O(log_k N), so more k
	 * means fewer rounds) and the budget tracks the convergence floor, as it should.
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

		// Convergence floor plus slack; see the maxIterations field for the full rationale. The slack
		// is max(k, alpha * allowance) so that small k still gets a usable depth/loss margin - the
		// previous 3*k left about three unanswered RPCs of margin at k=8 - while large k is carried by
		// the floor, which is where the real cost lives.
		this.maxIterations = KadConstants.LOOKUP_CONVERGENCE_FACTOR * k +
				Math.max(k, context.getAlpha() * KadConstants.LOOKUP_DEPTH_ALLOWANCE);

		this.closest = new ClosestSet(target, k);
		this.candidates = new ClosestCandidates(target, candidateCapacity(k), context.isDeveloperMode());
	}

	/**
	 * Returns how many candidates a lookup for the given bucket size will queue.
	 * <p>
	 * The k closest plus 2k spares to route around dead or hostile peers, capped by
	 * {@link KadConstants#MAX_LOOKUP_CANDIDATES} because the queue is re-sorted on every insertion and
	 * therefore costs CPU quadratic in its size. The cap binds only for large k, where the extra
	 * spares would never be consulted anyway - reaching them requires 2k peers ahead of them to fail.
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
	 * Returns a detailed string representation of the task’s state, including the closest nodes and candidates.
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