/*
 * Copyright (c) 2022 Elastos Foundation
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

package elastos.carrier.kademlia.tasks;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import elastos.carrier.kademlia.Constants;
import elastos.carrier.kademlia.DHT;
import elastos.carrier.kademlia.Id;
import elastos.carrier.kademlia.KBucketEntry;
import elastos.carrier.kademlia.KClosestNodes;
import elastos.carrier.kademlia.NodeInfo;
import elastos.carrier.kademlia.RPCCall;
import elastos.carrier.kademlia.messages.FindNodeRequest;
import elastos.carrier.kademlia.messages.FindNodeResponse;
import elastos.carrier.kademlia.messages.Message;
import elastos.carrier.utils.AddressUtils;


public class NodeLookup extends TargetedTask {
	private boolean bootstrap = false;
	private static final Logger log = LoggerFactory.getLogger(NodeLookup.class);

	public NodeLookup(DHT dht, Id nodeId, boolean bootstrap) {
		super(dht, nodeId);
		this.bootstrap = bootstrap;
	}

	public NodeLookup(DHT dht, Id nodeId) {
		this(dht, nodeId, false);
	}

	public void injectCandidates(Collection<NodeInfo> nodes) {
		addCandidates(nodes);
	}

	@Override
	protected void prepare() {
		// if we're bootstrapping start from the bucket that has the greatest possible
		// distance from ourselves so we discover new things along the (longer) path
		Id knsTarget = bootstrap ? getTarget().distance(Id.MAX_ID) : getTarget();

		// delay the filling of the todo list until we actually start the task
		KClosestNodes kns = new KClosestNodes(getDHT(), knsTarget, Constants.MAX_ENTRIES_PER_BUCKET * 2);
		kns.filter = KBucketEntry::eligibleForLocalLookup;
		kns.fill();
		addCandidates(kns.entries());
	}

	@Override
	protected void update() {
		for(;;) {
			CandidateNode cn = getNextCandidate();
			if(cn == null)
				return;

			// send a findNode to the node
			FindNodeRequest r = new FindNodeRequest(getTarget());
			r.setWant4(getDHT().getType() == DHT.Type.IPV4);
			r.setWant6(getDHT().getType() == DHT.Type.IPV6);

			boolean sent = !sendCall(cn, r, (c) -> {
				cn.setSent();
			});

			if (!sent)
				break;
		}
	}

	@Override
	protected void callResponsed(RPCCall call, Message response) {
		if (!call.matchesId())
			return; // Ignore

		if (response.getType() != Message.Type.RESPONSE || response.getMethod() != Message.Method.FIND_NODE)
			return;

		CandidateNode cn = removeCandidate(call.getTargetId());
		if (cn != null) {
			cn.setReplied();
			addClosest(cn);
		}

		FindNodeResponse fnr = (FindNodeResponse)response;

		// TODO: handle bouth IPv4 & IPv6 result
		List<NodeInfo> nodes = fnr.getNodes(getDHT().getType());
		if (nodes == null)
			return;

		Set<NodeInfo> cands = nodes.stream().filter(e -> !AddressUtils.isBogon(e.getAddress()) && !getDHT().getNode().isLocalId(e.getId())).collect(Collectors.toSet());
		addCandidates(cands);
	}

	@Override
	protected boolean isDone() {
		return getNextCandidate() == null && super.isDone();
	}

	@Override
	protected Logger getLogger() {
		return log;
	}
}

