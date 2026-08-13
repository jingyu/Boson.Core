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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * is dropped rather than re-verified - whether something at that address still answers is a different
 * question, and not the one being asked.
 * </p>
 * <p>
 * The other half is the refusal to learn the binding that reported the change. Without it, the reporting id
 * would take the address slot it just emptied and {@code KBucket.put} would then refuse the real peer on its
 * return, turning a single unverified packet into a lasting eviction. That is the property most of these
 * tests exist to hold.
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
	public void testChurnRemovesTheStaleEntry() {
		Id was = Id.random();
		seat(was, HOST, PORT);
		assertNotNull(dht.getRoutingTable().getEntry(was, true));

		dht.onChurn(NodeInfo.of(was, HOST, PORT));

		assertNull(dht.getRoutingTable().getEntry(was, true),
				"a binding that changed is not the long-lived contact the table exists to hold");
	}

	@Test
	public void testChurnRemovesAnEntryEvenWhenItWasJustHeardFrom() {
		// The criterion is stability, not liveness. An entry we heard from a moment ago goes just the same,
		// which is what stops this being "fixed" later into a reachability check.
		Id was = Id.random();
		KBucketEntry entry = seat(was, HOST, PORT);
		entry.onResponded(10);

		dht.onChurn(NodeInfo.of(was, HOST, PORT));

		assertNull(dht.getRoutingTable().getEntry(was, true));
	}

	@Test
	public void testChurnForAnUnknownIdIsANoOp() {
		Id resident = Id.random();
		seat(resident, HOST, PORT);

		dht.onChurn(NodeInfo.of(Id.random(), HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(resident, true), "an unrelated entry must not be touched");
	}

	@Test
	public void testChurnForAnIdLivingElsewhereIsANoOp() {
		// Churn is reported per endpoint, the table is keyed per id. An id that has since moved must not be
		// evicted for whatever now occupies the address it used to hold.
		Id moved = Id.random();
		seat(moved, "203.0.113.99", PORT);

		dht.onChurn(NodeInfo.of(moved, HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(moved, true),
				"the entry no longer lives at the address that churned");
	}

	@Test
	public void testTheReportingIdDoesNotTakeTheVacatedAddress() {
		// The lockout guard, and the reason the old code's early return had to come back. One unverified
		// packet may cost a peer its entry; it must not also hand the address to whoever sent that packet,
		// because KBucket.put would then refuse the real peer on its return.
		Id victim = Id.random();
		seat(victim, HOST, PORT);

		Id attacker = Id.random();
		dht.onChurn(NodeInfo.of(victim, HOST, PORT));
		dht.received(arriving(attacker, HOST, PORT));

		assertNull(dht.getRoutingTable().getEntry(victim, true), "the churned entry is gone");
		assertNull(dht.getRoutingTable().getEntry(attacker, true),
				"the id that reported the churn must not be learned from that message");
	}

	@Test
	public void testTheVictimCanReturnOnItsNextMessage() {
		// The cost of the refusal above is one message, for the attacker and the honest peer alike - so an
		// eviction stays transient rather than becoming a lockout.
		Id victim = Id.random();
		seat(victim, HOST, PORT);

		dht.onChurn(NodeInfo.of(victim, HOST, PORT));
		dht.received(arriving(Id.random(), HOST, PORT));
		dht.received(arriving(victim, HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(victim, true),
				"the peer is re-learned as soon as it speaks again");
	}

	@Test
	public void testTheMarkerIsConsumedOnce() {
		// Consume-on-read: the marker is spent by the message that reported the churn and must not swallow
		// the next one from that endpoint.
		Id was = Id.random();
		seat(was, HOST, PORT);

		dht.onChurn(NodeInfo.of(was, HOST, PORT));
		dht.received(arriving(Id.random(), HOST, PORT));

		Id next = Id.random();
		dht.received(arriving(next, HOST, PORT));

		assertNotNull(dht.getRoutingTable().getEntry(next, true), "only one message is refused");
	}

	@Test
	public void testTheMarkerDoesNotAffectAnotherEndpoint() {
		Id was = Id.random();
		seat(was, HOST, PORT);

		dht.onChurn(NodeInfo.of(was, HOST, PORT));

		Id elsewhere = Id.random();
		dht.received(arriving(elsewhere, HOST, PORT + 1));

		assertNotNull(dht.getRoutingTable().getEntry(elsewhere, true),
				"a different endpoint is a different peer");
		assertNull(dht.getRoutingTable().getEntry(was, true), "and the churned entry is still gone");
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

		dht.onChurn(NodeInfo.of(was, HOST, PORT));   // armed, and nothing is dispatched for it

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
