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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;

import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers what happens when a contact turns up at a different address than the table has for it - a NAT
 * remapping its port, or a laptop changing networks.
 * <p>
 * This is the mirror of identity churn, and it resolves the opposite way. Churn is <i>same address, new
 * id</i>, where the question is stability and the answer is to evict. This is <i>same id, new address</i>,
 * where the message authenticated under that id and so the contact itself is reporting the move - but a
 * packet that authenticates can be relayed by anyone who captures it, so the new address is still not
 * believed. The entry is demoted instead: kept, so a relay cannot displace it, and no longer treated as
 * good.
 * </p>
 * <p>
 * Demotion carries the whole benefit here. It stops the contradicted address being handed to other nodes,
 * it makes the event reportable exactly once - so a contact that really did move cannot accumulate
 * suppression hits simply for talking to us - and it revives the {@code failedRequests > 1 &&
 * !isReachable()} clause of {@code needsReplacement()}, which is dead for any contact that was ever
 * verified because a timeout never clears the flag.
 * </p>
 */
public class DHTAddressChangeTests {
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

	private KBucketEntry seat(Id id, String host, int port) {
		KBucketEntry entry = new KBucketEntry(id, new InetSocketAddress(host, port));
		entry.onResponded(50);
		dht.getRoutingTable().put(entry);
		return entry;
	}

	private Message arriving(Id from, String host, int port) {
		Message message = Message.pingRequest();
		message.setId(from);
		message.setRemote(from, SocketAddress.inetSocketAddress(port, host));
		return message;
	}

	@Test
	public void testTheNewAddressIsNotLearned() {
		// A packet that authenticates as this id can still have been relayed, so believing its source would
		// let one captured packet move any contact to an address the attacker picked.
		Id moved = Id.random();
		seat(moved, HOST, PORT);

		dht.received(arriving(moved, "198.51.100.9", 40001));

		KBucketEntry entry = dht.getRoutingTable().getEntry(moved, true);
		assertNotNull(entry, "the entry is kept - demotion is not eviction");
		assertEquals(HOST, entry.getIpAddress().getHostAddress(), "and still holds the old address");
		assertEquals(PORT, entry.getPort());
	}

	@Test
	public void testTheFirstContradictionDemotesTheEntry() {
		Id moved = Id.random();
		seat(moved, HOST, PORT);
		assertTrue(dht.getRoutingTable().getEntry(moved, true).isReachable());

		dht.received(arriving(moved, "198.51.100.9", 40001));

		assertFalse(dht.getRoutingTable().getEntry(moved, true).isReachable(),
				"we have been told this address is wrong, so we stop vouching for it");
	}

	@Test
	public void testFurtherContradictionsChangeNothing() {
		// The flag is also the latch. A contact that really moved keeps talking to us, and each of those
		// messages used to cost it a suppression hit - enough of them and it would have been suppressed for
		// having changed address.
		Id moved = Id.random();
		seat(moved, HOST, PORT);

		for (int i = 0; i < 64; i++)
			dht.received(arriving(moved, "198.51.100.9", 40001 + i));

		KBucketEntry entry = dht.getRoutingTable().getEntry(moved, true);
		assertNotNull(entry);
		assertFalse(entry.isReachable());
		assertEquals(0, entry.failedRequests(), "nothing here counts as a failure of the contact itself");
	}

	@Test
	public void testAContactAtItsKnownAddressIsUnaffected() {
		// The control: the ordinary case must not be demoted by any of the above.
		Id known = Id.random();
		seat(known, HOST, PORT);

		dht.received(arriving(known, HOST, PORT));

		assertTrue(dht.getRoutingTable().getEntry(known, true).isReachable(),
				"an address that matches is not a contradiction");
	}

	// The acceleration this demotion buys - two failures retire the entry where six were needed - is a
	// routing-table property and is pinned by RoutingTableTests.testDemotionMakesAnEntryRetirableAfterTwoFailures,
	// where needsReplacement() is visible.
}
