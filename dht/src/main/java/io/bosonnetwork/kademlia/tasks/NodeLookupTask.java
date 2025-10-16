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

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.FindNodeRequest;
import io.bosonnetwork.kademlia.protocol.FindNodeResponse;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.routing.KClosestNodes;
import io.bosonnetwork.kademlia.rpc.RpcCall;

/**
 * A task for performing a Kademlia node lookup to find the closest nodes to a target ID.
 * This task issues {@code FIND_NODE} RPCs to iteratively refine a set of closest nodes,
 * supporting bootstrap mode for network discovery and optional token requests for subsequent
 * operations. It extends {@link LookupTask} to leverage its candidate management and RPC handling
 * in a single-threaded Vert.x event loop.
 */
public class NodeLookupTask extends LookupTask<NodeInfo, NodeLookupTask> {
	/** Whether this is a bootstrap lookup, starting from nodes farthest from the local node. */
	private boolean bootstrap = false;
	/** Whether to request tokens in FIND_NODE RPCs for subsequent operations. */
	private boolean wantToken = false;

	private static final Logger log = LoggerFactory.getLogger(NodeLookupTask.class);

	/**
	 * Constructs a new node lookup task for the given target ID.
	 *
	 * @param context the Kademlia context, must not be null
	 * @param target  the target ID to look up
	 */
	public NodeLookupTask(KadContext context, Id target) {
		super(context, target);
	}

	/**
	 * Configures the task as a bootstrap lookup, starting from nodes farthest from the local node.
	 *
	 * @param bootstrap true to enable bootstrap mode, false otherwise
	 * @return this task for method chaining
	 */
	public NodeLookupTask setBootstrap(boolean bootstrap) {
		this.bootstrap = bootstrap;
		return this;
	}

	/**
	 * Checks if the task is in bootstrap mode.
	 *
	 * @return true if bootstrap mode is enabled, false otherwise
	 */
	public boolean isBootstrap() {
		return bootstrap;
	}

	/**
	 * Configures the task to request tokens in FIND_NODE RPCs.
	 *
	 * @param wantToken true to request tokens, false otherwise
	 * @return this task for method chaining
	 */
	public NodeLookupTask setWantToken(boolean wantToken) {
		this.wantToken = wantToken;
		return this;
	}

	/**
	 * Checks if the task requests tokens in FIND_NODE RPCs.
	 *
	 * @return true if tokens are requested, false otherwise
	 */
	public boolean doesWantToken() {
		return wantToken;
	}

	/**
	 * Injects a collection of nodes as initial candidates for the lookup.
	 * Useful for testing or seeding the lookup with known nodes.
	 *
	 * @param nodes the nodes to add as candidates
	 * @return this task for method chaining
	 */
	public NodeLookupTask injectCandidates(Collection<NodeInfo> nodes) {
		if (!nodes.isEmpty())
			addCandidates(nodes);
		return this;
	}

	/**
	 * Prepares the task by initializing the candidate list from the routing table.
	 * In bootstrap mode, starts from nodes farthest from the local node to aid network discovery.
	 */
	@Override
	protected void prepare() {
		// In bootstrap mode, use the maximum distance from the target to start from nodes farthest from the local node
		Id knsTarget = bootstrap ? getTarget().distance(Id.MAX_ID) : getTarget();

		// delay the filling of the candidate list until we actually start the task
		KClosestNodes kns = getContext().getDHT().getRoutingTable()
				.getClosestNodes(knsTarget, KBucket.MAX_ENTRIES * 3)
				.filter(KBucketEntry::eligibleForLocalLookup)
				.fill();
		log.debug("{}#{} initialized {} candidates for target {}", getName(), getId(), kns.entries().size(), knsTarget);
		addCandidates(kns.entries());
	}

	/**
	 * Performs one iteration of the lookup, sending FIND_NODE RPCs to candidate nodes.
	 */
	@Override
	protected void iterate() {
		super.iterate();
		while (!isCandidatesEmpty() && canDoRequest()) {
			CandidateNode cn = getNextCandidate();
			if (cn == null) {
				// no eligible candidates right now, check in the next iteration
				log.debug("{}#{} no eligible candidates in non-empty queue", getName(), getId());
				break;
			}

			// Send a FIND_NODE request to the candidate
			Network network = getContext().getNetwork();
			Message<FindNodeRequest> request = Message.findNodeRequest(getTarget(),
					network.isIPv4(), network.isIPv6(), doesWantToken());

			log.debug("{}#{} sending FIND_NODE RPC to candidate {}", getName(), getId(), cn.getId());
			sendCall(cn, request, (c) -> cn.setSent());
		}
	}

	/**
	 * Handles a FIND_NODE response, adding returned nodes to candidates and filtering exact matches.
	 * Assumes the RPC server provides a valid response.
	 *
	 * @param call the RPC call with a response
	 */
	@Override
	protected void callResponded(RpcCall call) {
		super.callResponded(call);

		if (call.isIdMismatched()) {
			log.debug("{}#{} ignoring mismatched ID response from {}", getName(), getId(), call.getTargetId());
			return;
		}

		Message<FindNodeResponse> response = call.getResponse();
		// TODO: handle both IPv4 & IPv6 result
		List<NodeInfo> nodes = response.getBody().getNodes(getContext().getNetwork());
		if (nodes.isEmpty()) {
			log.debug("{}#{} empty node list in response from {}", getName(), getId(), call.getTargetId());
			return;
		}

		// TODO: Check for sibling DHT4 (IPv4) or DHT6 (IPv6) network and forward nodes matching the sibling's protocol
		/*/
		if (!getContext().getNetwork().isIPv4() || !getContext().getNetwork().isIPv6()) {
			log.debug("Handling {} nodes; IPv4={}, IPv6={}",
					getContext().getNetwork().isIPv4() ? "IPv4" : "IPv6",
					getContext().getNetwork().isIPv4(), getContext().getNetwork().isIPv6());
		}
		*/

		log.debug("{}#{} adding {} candidates from response by {}", getName(), getId(), nodes.size(), call.getTargetId());
		addCandidates(nodes);

		if (resultFilter != null) {
			// Check for nodes matching the target ID
			for (NodeInfo node : nodes) {
				if (node.getId().equals(getTarget())) {
					ResultFilter.Action action = resultFilter.apply(getResult(), node);
					log.debug("{}#{} filtered node {}: action={}", getName(), getId(), node.getId(), action);
					if (action.isAccept())
						setResult(node);
					if (action.isDone())
						lookupDone = true;
				}
			}
		}
	}

	/**
	 * Returns the logger for this task.
	 *
	 * @return the logger instance
	 */
	@Override
	protected Logger getLogger() {
		return log;
	}
}