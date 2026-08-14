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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import io.vertx.core.Future;
import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * The ping a new contact draws is marked as what it is: a call an arriving packet made us make.
 * <p>
 * It is an optimization - a not-yet-reachable entry is pinged now instead of waiting for the maintenance
 * pass - and its rate belongs to whoever is sending us requests rather than to us. That is the whole reason
 * for the mark: on the transport side it puts the call on a sub-budget of the active-call table, so a sender
 * cannot spend the slots the tasks are sized against. Unmarked, the call is indistinguishable from one of
 * ours and draws on the whole table, which is what it used to do.
 * </p>
 * <p>
 * Never deployed: the routing table is built by the constructor, and the send is intercepted rather than
 * made, so nothing here needs a socket.
 * </p>
 */
public class DHTUnsolicitedPingTests {
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	private static final String HOST = "203.0.113.7";
	private static final int PORT = 39001;

	/** A DHT that keeps what it would have sent, in the idiom {@code Task.sendCall} already uses. */
	private static class CapturingDHT extends DHT {
		final List<RpcCall> sent = new ArrayList<>();

		CapturingDHT(Identity identity) {
			super(identity, Network.IPv4, "127.0.0.1", 39101, List.of(),
					ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
					null, null, new TokenManager(),
					Blacklist.empty(), false, false, true, null);
		}

		@Override
		Future<RpcCall> sendCallInternal(RpcCall call) {
			sent.add(call);
			return Future.succeededFuture(call);
		}
	}

	private CapturingDHT dht;

	@BeforeEach
	void setUp() {
		dht = new CapturingDHT(new CryptoIdentity());
	}

	/** A message as the receive path would hand it over: parsed, with its sender and source address set. */
	private static Message arriving(Id from, String host, int port) {
		Message message = Message.pingRequest();
		message.setId(from);
		message.setRemote(from, SocketAddress.inetSocketAddress(port, host));
		return message;
	}

	@Test
	public void testThePingForANewContactIsMarkedUnsolicited() {
		Id newcomer = Id.random();
		dht.received(arriving(newcomer, HOST, PORT));

		assertEquals(1, dht.sent.size(), "a request from an id we do not hold should draw one ping");

		RpcCall ping = dht.sent.get(0);
		assertEquals(newcomer, ping.getTargetId());
		assertTrue(ping.isUnsolicited(),
				"the ping is charged to the table the tasks use unless it is marked as provoked");
	}

	/**
	 * And only that one. A contact that has already answered us is not pinged again, so nothing here can
	 * turn the mark into a habit that spreads to the calls we make on our own account.
	 */
	@Test
	public void testAKnownReachableContactDrawsNoPing() {
		Id known = Id.random();
		KBucketEntry entry = new KBucketEntry(known, new InetSocketAddress(HOST, PORT));
		entry.onResponded(50);
		dht.getRoutingTable().put(entry);

		dht.received(arriving(known, HOST, PORT));

		assertTrue(dht.sent.isEmpty(), "a contact that has answered needs no ping to prove it");
	}
}
