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

package io.bosonnetwork.kademlia.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;

import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers what the DHT does when an endpoint presents a different node id than it presented before.
 * <p>
 * The routing table is worth having because it holds contacts that have been reachable a long time and are
 * therefore likely to stay. An identity that changes at a fixed address has failed that test, so the entry
 * stops being treated as good - whether something at that address still answers is a different question, and
 * not the one being asked.
 * </p>
 * <p>
 * <b>How far it stops depends on who says so</b>, which is the seam these tests are built along. A response
 * that matched one of our calls, from the address we sent it to, proves the contact itself churned and the
 * entry is removed. Any other message only <i>observes</i> the change, and its source address is written by
 * its sender - so acting on it as strongly would hand a spoofer an eviction aimed at any endpoint it can
 * name. That report demotes instead: kept, no longer vouched for, undone by the peer's next answer.
 * </p>
 * <p>
 * The other half, shared by both, is the refusal to learn the binding that reported the change - so that a
 * packet contradicting what we hold at an endpoint cannot also install itself there.
 * </p>
 * <p>
 * Never deployed: the routing table is built by the constructor, and both halves are pure functions of it.
 * </p>
 */
public class DHTChurnHandlerTests {
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	private static final String HOST = "203.0.113.7";
	private static final int PORT = 39001;

	private DHT dht;

	@BeforeEach
	void setUp() {
		dht = new DHT(new CryptoIdentity(), Network.IPv4, "127.0.0.1", 39101, List.of(),
				ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
				null, null, new TokenManager(),
				Blacklist.empty(), false, false, true, null);
	}

	/**
	 * Puts a reachable entry into the table, as a node that answered would be.
	 */
	private KBucketEntry seat(Id id, String host, int port) {
		KBucketEntry entry = new KBucketEntry(id, new InetSocketAddress(host, port));
		entry.onResponded(50);
		dht.getRoutingTable().put(entry);
		return entry;
	}

	/**
	 * A message as the receive path would hand it over: parsed, with its sender and source address set.
	 */
	private Message arriving(Id from, String host, int port) {
		Message message = Message.pingRequest();
		message.setId(from);
		message.setRemote(from, SocketAddress.inetSocketAddress(port, host));
		return message;
	}

	@Test
	public void testObservedChurnDemotesTheStaleEntry() {
		// Unproven: kept, and no longer vouched for. Removing here would be an eviction primitive a spoofer
		// aims at any endpoint it can name - and nothing bounds that, because the inbound throttle counts
		// against the address on the packet, which in that attack is the victim's.
		Id was = Id.random();
		seat(was, HOST, PORT);
		assertTrue(dht.getRoutingTable().getEntry(was, true).isReachable());

		dht.onChurn(NodeInfo.of(was, HOST, PORT), false);

		KBucketEntry entry = dht.getRoutingTable().getEntry(was, true);
		assertNotNull(entry, "an unproven report must not be able to evict a contact");
		assertFalse(entry.isReachable(), "but the binding that changed is no longer vouched for");
	}

	@Test
	public void testTheDemotionIsRevocableByEvidence() {
		// What makes demotion the right level: a spoofed report costs the peer one exchange, not its place.
		Id was = Id.random();
		KBucketEntry entry = seat(was, HOST, PORT);

		dht.onChurn(NodeInfo.of(was, HOST, PORT), false);
		assertFalse(entry.isReachable());

		entry.onResponded(20);
		assertTrue(entry.isReachable(), "an answer from the contact undoes a demotion it did not deserve");
	}

	@Test
	public void testFurtherObservedChurnChangesNothing() {
		// markUnreachable is its own latch, which is also what keeps the suppression hit once per change of
		// state rather than once per packet.
		Id was = Id.random();
		seat(was, HOST, PORT);

		dht.onChurn(NodeInfo.of(was, HOST, PORT), false);
		dht.onChurn(NodeInfo.of(was, HOST, PORT), false);
		dht.onChurn(NodeInfo.of(was, HOST, PORT), false);

		KBucketEntry entry = dht.getRoutingTable().getEntry(was, true);
		assertNotNull(entry, "repeating an unproven report must not escalate it into an eviction");
		assertFalse(entry.isReachable());
	}

	@Test
	public void testProvenChurnRemovesTheStaleEntry() {
		Id was = Id.random();
		seat(was, HOST, PORT);
		assertNotNull(dht.getRoutingTable().getEntry(was, true));

		dht.onChurn(NodeInfo.of(was, HOST, PORT), true);

		assertNull(dht.getRoutingTable().getEntry(was, true),
				"a binding the contact itself changed is not the long-lived contact the table exists to hold");
	}

	@Test
	public void testProvenChurnRemovesAnEntryEvenWhenItWasJustHeardFrom() {
		// The criterion is stability, not liveness. An entry we heard from a moment ago goes just the same,
		// which is what stops this being "fixed" later into a reachability check.
		Id was = Id.random();
		KBucketEntry entry = seat(was, HOST, PORT);
		entry.onResponded(10);

		dht.onChurn(NodeInfo.of(was, HOST, PORT), true);

		assertNull(dht.getRoutingTable().getEntry(was, true));
	}

	@Test
	public void testChurnForAnUnknownIdIsANoOp() {
		Id resident = Id.random();
		seat(resident, HOST, PORT);

		dht.onChurn(NodeInfo.of(Id.random(), HOST, PORT), false);
		dht.onChurn(NodeInfo.of(Id.random(), HOST, PORT), true);

		KBucketEntry entry = dht.getRoutingTable().getEntry(resident, true);
		assertNotNull(entry, "an unrelated entry must not be touched");
		assertTrue(entry.isReachable(), "nor demoted");
	}

	@Test
	public void testChurnForAnIdLivingElsewhereIsANoOp() {
		// Churn is reported per endpoint, the table is keyed per id. An id that has since moved must not be
		// touched for whatever now occupies the address it used to hold.
		Id moved = Id.random();
		seat(moved, "203.0.113.99", PORT);

		dht.onChurn(NodeInfo.of(moved, HOST, PORT), false);
		dht.onChurn(NodeInfo.of(moved, HOST, PORT), true);

		KBucketEntry entry = dht.getRoutingTable().getEntry(moved, true);
		assertNotNull(entry, "the entry no longer lives at the address that churned");
		assertTrue(entry.isReachable());
	}

	@Test
	public void testTheReportingIdIsNotLearnedFromThatMessage() {
		// A packet that contradicts what we hold at an endpoint must not install itself there either. The
		// entry it named is kept, so the attacker gains nothing at that address in this bucket - but ids are
		// free, and the address-collision check is per bucket, so an id chosen to land elsewhere would be
		// admitted at the victim's address if the message were learned. The marker is what refuses it.
		Id victim = Id.random();
		seat(victim, HOST, PORT);

		Id attacker = Id.random();
		dht.onChurn(NodeInfo.of(victim, HOST, PORT), false);
		dht.received(arriving(attacker, HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(victim, true), "the contact is kept");
		assertNull(dht.getRoutingTable().getEntry(attacker, true),
				"the id that reported the churn must not be learned from that message");
	}

	@Test
	public void testTheReportingIdDoesNotTakeTheVacatedAddress() {
		// The lockout guard on the path that does remove, and the reason the old code's early return had to
		// come back: without it the reporting id takes the address slot the removal just emptied, and
		// KBucket.put then refuses the real peer on its return.
		Id victim = Id.random();
		seat(victim, HOST, PORT);

		Id reporter = Id.random();
		dht.onChurn(NodeInfo.of(victim, HOST, PORT), true);
		dht.received(arriving(reporter, HOST, PORT));

		assertNull(dht.getRoutingTable().getEntry(victim, true), "the churned entry is gone");
		assertNull(dht.getRoutingTable().getEntry(reporter, true),
				"the id that reported the churn must not be learned from that message");
	}

	@Test
	public void testTheVictimCanReturnOnItsNextMessage() {
		// The cost of the refusal above is one message, for whoever sent it and the honest peer alike - so a
		// removal stays transient rather than becoming a lockout.
		Id victim = Id.random();
		seat(victim, HOST, PORT);

		dht.onChurn(NodeInfo.of(victim, HOST, PORT), true);
		dht.received(arriving(Id.random(), HOST, PORT));
		dht.received(arriving(victim, HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(victim, true),
				"the peer is re-learned as soon as it speaks again");
	}

	@Test
	public void testTheMarkerIsConsumedOnce() {
		// Consume-on-read: the marker is spent by the message that reported the churn and must not swallow
		// the next one from that endpoint.
		//
		// Driven through the removing path on purpose. After a demotion the entry is still there holding
		// that address, so a later id from the same endpoint is refused by KBucket.put rather than by the
		// marker, and the two causes would be indistinguishable here.
		Id was = Id.random();
		seat(was, HOST, PORT);

		dht.onChurn(NodeInfo.of(was, HOST, PORT), true);
		dht.received(arriving(Id.random(), HOST, PORT));

		Id next = Id.random();
		dht.received(arriving(next, HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(next, true), "only one message is refused");
	}

	@Test
	public void testTheMarkerDoesNotAffectAnotherEndpoint() {
		Id was = Id.random();
		seat(was, HOST, PORT);

		dht.onChurn(NodeInfo.of(was, HOST, PORT), false);

		Id elsewhere = Id.random();
		dht.received(arriving(elsewhere, HOST, PORT + 1));

		assertNotNull(dht.getRoutingTable().getEntry(elsewhere, true),
				"a different endpoint is a different peer");
		assertFalse(dht.getRoutingTable().getEntry(was, true).isReachable(),
				"and the churned entry is still demoted");
	}

	@Test
	public void testAnArmedMarkerDoesNotOutliveTheNextMessage() {
		// Not every churn report is followed by a dispatch: when a response carries a different id than the
		// request was addressed to, the RPC server answers the call and returns without handing the message
		// on. The marker armed by that report has nothing to refuse, and must not sit waiting to swallow
		// something unrelated later - which is why it is cleared on the way through rather than only on a
		// match.
		Id was = Id.random();
		seat(was, HOST, PORT);

		dht.onChurn(NodeInfo.of(was, HOST, PORT), true);   // armed, and nothing is dispatched for it

		// Any next message spends it, whatever endpoint it came from.
		Id other = Id.random();
		dht.received(arriving(other, "203.0.113.8", PORT));
		assertNotNull(dht.getRoutingTable().getEntry(other, true));

		Id next = Id.random();
		dht.received(arriving(next, HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(next, true),
				"a marker with no message to refuse must not outlive the one that follows it");
	}
}
