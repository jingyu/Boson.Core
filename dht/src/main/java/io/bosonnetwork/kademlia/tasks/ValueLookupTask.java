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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.FindValueRequest;
import io.bosonnetwork.kademlia.protocol.FindValueResponse;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.routing.KClosestNodes;
import io.bosonnetwork.kademlia.rpc.RpcCall;

/**
 * A task for performing a Kademlia value lookup to find a value associated with a target ID,
 * typically a key in a distributed hash table. This task issues {@code FIND_VALUE} RPCs to
 * retrieve a value or nodes, validating responses for ID, signature, and sequence number.
 * It extends {@link LookupTask} to leverage its candidate management and RPC handling
 * in a single-threaded Vert.x event loop.
 */
public class ValueLookupTask extends LookupTask<EligibleValue, ValueLookupTask> {
	/** The expected sequence number for filtering outdated values; -1 disables the check. */
	private final int expectedSequenceNumber;

	private static final Logger log = LoggerFactory.getLogger(ValueLookupTask.class);

	/**
	 * Constructs a new value lookup task for the given target ID and expected sequence number.
	 *
	 * @param context               the Kademlia context, must not be null
	 * @param target                the target ID (e.g., key hash) to look up
	 * @param expectedSequenceNumber the minimum sequence number for valid values; -1 to disable
	 * @param doneOnEligibleResult true if the lookup is complete when a result is eligible, false continue
	 */
	public ValueLookupTask(KadContext context, Id target, int expectedSequenceNumber, boolean doneOnEligibleResult) {
		super(context, target, doneOnEligibleResult);
		this.expectedSequenceNumber = expectedSequenceNumber;
		setResult(new EligibleValue(target, expectedSequenceNumber));
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
	 * Performs one iteration of the lookup, sending FIND_VALUE RPCs to candidate nodes.
	 */
	@Override
	protected void iterate() {
		super.iterate();
		log.trace("{}#{} candidates.size={}", getName(), getId(), getCandidateSize());
		while (!isCandidatesEmpty() && canDoRequest()) {
			CandidateNode cn = getNextCandidate();
			if (cn == null) {
				// no eligible candidates right now, check in the next iteration
				log.debug("{}#{} no eligible candidates in non-empty queue", getName(), getId());
				break;
			}

			log.debug("{}#{} sending FIND_VALUE RPC to {}", getName(), getId(), cn.getId());
			Network network = getContext().getNetwork();
			Message<FindValueRequest> request = Message.findValueRequest(getTarget(),
					network.isIPv4(), network.isIPv6(), expectedSequenceNumber);
			sendCall(cn, request, c -> cn.setSent());
		}
	}

	/**
	 * Handles a FIND_VALUE response, processing a value or nodes and updating the result.
	 * Drops the entire response if the value has a mismatched ID, invalid signature, or outdated
	 * sequence number, as the node is considered unqualified. Assumes the RPC server provides a valid response.
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

		Message<FindValueResponse> response = call.getResponse();
		if (response.getBody().hasValue()) {
			Value value = response.getBody().getValue();
			if (!result.update(value)) {
				log.warn("{}#{} dropping response from {} due to ineligible value(id | sequenceNumber | signature mismatch)",
						getName(), getId(), call.getTargetId());
				return;
			}

			if (!result.isEmpty()) {
				if (doneOnEligibleResult) {
					log.debug("{}#{} value is eligible, done on result", getName(), getId());
					lookupDone = true;
				} else {
					log.trace("{}#{} value is eligible, continuing iteration for precise result", getName(), getId());
				}
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