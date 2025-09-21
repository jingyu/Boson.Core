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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.FindPeerRequest;
import io.bosonnetwork.kademlia.protocol.FindPeerResponse;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.routing.KClosestNodes;
import io.bosonnetwork.kademlia.rpc.RpcCall;

/**
 * A task for performing a Kademlia peer lookup to find peers associated with a target ID,
 * typically a content hash for BitTorrent-style peer discovery. This task issues {@code FIND_PEER}
 * RPCs to retrieve peers or nodes, merging valid peers into a deduplicated result list.
 * It extends {@link LookupTask} to leverage its candidate management and RPC handling
 * in a single-threaded Vert.x event loop.
 */
public class PeerLookupTask extends LookupTask<List<PeerInfo>, PeerLookupTask> {
	private static final Logger log = LoggerFactory.getLogger(PeerLookupTask.class);

	/**
	 * Constructs a new peer lookup task for the given target ID, initializing an empty result list.
	 *
	 * @param context the Kademlia context, must not be null
	 * @param target  the target ID (e.g., content hash) to look up
	 */
	public PeerLookupTask(KadContext context, Id target) {
		super(context, target);
		setResult(Collections.emptyList());
	}

	/**
	 * Prepares the task by initializing the candidate list from the routing table.
	 */
	@Override
	protected void prepare() {
		// delay the filling of the candidate list until we actually start the task
		KClosestNodes kns = getContext().getDHT().getRoutingTable()
				.getClosestNodes(getTarget(), KBucket.MAX_ENTRIES * 3)
				.filter(KBucketEntry::eligibleForLocalLookup)
				.fill();
		log.debug("{}#{} initialized {} candidates for target {}", getName(), getId(), kns.entries().size(), getTarget());
		addCandidates(kns.entries());
	}

	/**
	 * Performs one iteration of the lookup, sending FIND_PEER RPCs to candidate nodes.
	 */
	@Override
	protected void iterate() {
		super.iterate();
		log.trace("{}#{} candidates.size={}", getName(), getId(), getCandidateSize());
		while (!isCandidatesEmpty() && canDoRequest()) {
			CandidateNode cn = getNextCandidate();
			if (cn == null) {
				// no eligible candidates right now, check in the next iteration
				log.warn("{}#{} no eligible candidates in non-empty queue", getName(), getId());
				break;
			}

			log.debug("{}#{} sending FIND_PEER RPC to {}", getName(), getId(), cn.getId());
			Network network = getContext().getNetwork();
			Message<FindPeerRequest> request = Message.findPeerRequest(getTarget(), network.isIPv4(), network.isIPv6());
			sendCall(cn, request, c -> cn.setSent());
		}
	}

	/**
	 * Merges two lists of peers, deduplicating by node ID to ensure uniqueness.
	 * Peers share the same peer ID (e.g., content hash) but differ in node ID.
	 *
	 * @param existing the existing peer list, or null if none
	 * @param next     the new peer list to merge
	 * @return a deduplicated list of peers
	 */
	private List<PeerInfo> mergeList(List<PeerInfo> existing, List<PeerInfo> next) {
		Map<Id, PeerInfo> dedup = new HashMap<>(next.size() + (existing != null ? existing.size() : 0));
		if (existing != null && !existing.isEmpty())
			existing.forEach(p -> dedup.put(p.getNodeId(), p));
		next.forEach(p -> dedup.put(p.getNodeId(), p));
		return new ArrayList<>(dedup.values());
	}

	/**
	 * Handles a FIND_PEER response, processing peers or nodes and updating the result.
	 * Assumes the RPC server provides a valid response. Drops the entire response if any peer is invalid,
	 * as the node is considered unqualified.
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

		Message<FindPeerResponse> response = call.getResponse();
		if (response.getBody().hasPeers()) {
			List<PeerInfo> peers = response.getBody().getPeers();
			for (PeerInfo peer : peers) {
				if (!peer.isValid()) {
					log.warn("{}#{} Dropping response from {} due to invalid peer (signature mismatch): {}",
							getName(), getId(), call.getTargetId(), peer.getNodeId());
					return;
				}
			}

			List<PeerInfo> merged = mergeList(getResult(), peers);
			log.debug("{}#{} merged {} peers from response by {}", getName(), getId(), peers.size(), call.getTargetId());
			if (resultFilter != null) {
				ResultFilter.Action action = resultFilter.apply(getResult(), merged);
				log.debug("{}#{} filtered peer list: action={}", getName(), getId(), action);
				if (action.isAccept())
					setResult(merged);
				if (action.isDone())
					lookupDone = true;
			} else {
				setResult(merged);
			}
		} else {
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

			addCandidates(nodes);
			log.debug("{}#{} added {} candidates from response by {}", getName(), getId(), nodes.size(), call.getTargetId());
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