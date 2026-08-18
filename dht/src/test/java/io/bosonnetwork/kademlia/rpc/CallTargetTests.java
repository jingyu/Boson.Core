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

package io.bosonnetwork.kademlia.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.tasks.CandidateNode;

/**
 * What an {@link RpcCall} takes from its target, and - just as much - what it must not invent.
 */
class CallTargetTests {
	private static InetSocketAddress address() {
		return new InetSocketAddress("192.168.7.9", 39001);
	}

	@Test
	void testAPlainNodeInfoCarriesNoLocalKnowledge() {
		// A node somebody described to us. We have never spoken to it, so both answers are absent.
		RpcCall call = new RpcCall(NodeInfo.of(Id.random(), address()), Message.pingRequest());

		assertFalse(call.isReachableAtCreationTime());
		assertFalse(call.isSetExpectedRTT(), "an unknown node must not arrive with an RTT already decided");
	}

	@Test
	void testACandidateWeWereOnlyToldAboutLeavesTheRttToTheSampler() {
		// The population this matters for: nearly every lookup candidate is learned from someone else's
		// response. Naming a constant RTT here would satisfy setExpectedRttIfAbsent and silently pin the
		// stall timeout to the maximum, which is the opposite of what an untimed node needs.
		CandidateNode cn = new CandidateNode(NodeInfo.of(Id.random(), address()));
		RpcCall call = new RpcCall(cn, Message.pingRequest());

		assertFalse(call.isReachableAtCreationTime());
		assertFalse(call.isSetExpectedRTT(), "a candidate we have never timed must leave the RTT to the sampler");
	}

	@Test
	void testACandidateSeededFromTheRoutingTableKeepsWhatTheEntryKnew() {
		// The half that was being thrown away: a candidate seeded from a verified routing table entry is
		// a binding we established ourselves, and the call is entitled to say so.
		KBucketEntry entry = new KBucketEntry(Id.random(), address());
		entry.onResponded(120);
		assertTrue(entry.isReachable());

		CandidateNode cn = new CandidateNode(entry);
		RpcCall call = new RpcCall(cn, Message.pingRequest());

		assertTrue(call.isReachableAtCreationTime(), "a candidate from a verified entry is verified");
		assertEquals(entry.getRTT(), call.getExpectedRTT(), "and it inherits that entry's timing");
	}

	@Test
	void testARoutingTableEntryTargetIsUnchanged() {
		// The path that already worked, pinned so the shared interface cannot quietly alter it.
		KBucketEntry entry = new KBucketEntry(Id.random(), address());
		entry.onResponded(120);

		RpcCall call = new RpcCall(entry, Message.pingRequest());

		assertTrue(call.isReachableAtCreationTime());
		assertEquals(entry.getRTT(), call.getExpectedRTT());
	}
}
