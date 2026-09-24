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
import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers what a DHT does with the endpoint replying nodes report seeing: what it calls itself, and what
 * it hands out as itself when a routing table cannot fill an answer.
 * <p>
 * The deployment behind all of it: a cloud server bound to a private address that the provider maps an
 * elastic address onto. The node must bind the private one, and must not hand it out.
 * </p>
 */
public class DHTPublicEndpointTests {
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	private static final InetSocketAddress PUBLIC = new InetSocketAddress("155.138.245.211", 39101);
	private static final InetSocketAddress ELSEWHERE = new InetSocketAddress("45.32.138.246", 39101);

	// Never deployed: the node's own info and an empty routing table are all these tests read.
	private static DHT dht(String host, boolean developerMode) {
		return new DHT(new CryptoIdentity(), Network.IPv4, host, 39101, List.of(),
				ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
				null, null, new TokenManager(),
				Blacklist.empty(), false, false, developerMode, null);
	}

	private static void agreeOn(DHT dht, InetSocketAddress endpoint) {
		agreeOn(dht, endpoint, 1, KadConstants.PUBLIC_ENDPOINT_MIN_REPORTERS);
	}

	// Reporters numbered first to last, each from a distinct source.
	private static void agreeOn(DHT dht, InetSocketAddress endpoint, int first, int last) {
		for (int i = first; i <= last; i++)
			dht.onObservedEndpoint(new InetSocketAddress("64.227.0." + i, 39001), endpoint);
	}

	// What an empty routing table answers: nothing but, possibly, ourselves.
	private static List<? extends NodeInfo> answer(DHT dht) {
		return dht.populateClosestNodes(Id.random(), 8, 0).result().nodes4();
	}

	@Test
	void aPrivatelyBoundNodeHandsOutNothingUntilItsPublicEndpointIsAgreed() {
		DHT dht = dht("10.0.0.10", false);

		assertEquals("10.0.0.10", dht.getNodeInfo().getHost(), "until then it is what it binds");
		assertTrue(answer(dht).isEmpty(), "a private address would only be dropped by every receiver");

		agreeOn(dht, PUBLIC);

		assertEquals(PUBLIC, dht.getNodeInfo().getAddress());
		assertEquals(List.of(dht.getNodeInfo()), answer(dht));
		assertEquals(1, dht.getPublicEndpointHistory().size());
	}

	@Test
	void aDirectlyAddressedNodeIsRightFromTheStart() {
		// A public address on the interface: nothing to wait for, and nothing changes when it is confirmed.
		DHT dht = dht(PUBLIC.getAddress().getHostAddress(), false);
		assertEquals(List.of(dht.getNodeInfo()), answer(dht));

		agreeOn(dht, PUBLIC);
		assertEquals(PUBLIC, dht.getNodeInfo().getAddress());
	}

	@Test
	void trackingStopsOnceTheBoundPublicAddressIsAgreed() {
		// It cannot change while the socket stays bound to it, so from then on nothing moves it - not even
		// reporters that agree on something else.
		DHT dht = dht(PUBLIC.getAddress().getHostAddress(), false);
		agreeOn(dht, PUBLIC);
		agreeOn(dht, ELSEWHERE, 10, 20);

		assertEquals(PUBLIC, dht.getNodeInfo().getAddress());
		assertEquals(1, dht.getPublicEndpointHistory().size(), "the confirmation stays on record");
	}

	@Test
	void anEarlierEndpointDoesNotOutliveTheBoundAddressWinning() {
		// Agreement first settled elsewhere - a multihomed host egressing through another address, say -
		// and then the bound public address out-voted it. The node must go back to the bound one, not
		// stop tracking while still handing out the endpoint that just lost.
		DHT dht = dht(PUBLIC.getAddress().getHostAddress(), false);
		agreeOn(dht, ELSEWHERE, 1, 3);
		assertEquals(ELSEWHERE, dht.getNodeInfo().getAddress());

		agreeOn(dht, PUBLIC, 4, 7);
		assertEquals(PUBLIC, dht.getNodeInfo().getAddress());

		// And tracking has stopped: a fresh majority for the old endpoint changes nothing.
		agreeOn(dht, ELSEWHERE, 10, 20);
		assertEquals(PUBLIC, dht.getNodeInfo().getAddress());
		assertEquals(2, dht.getPublicEndpointHistory().size());
	}

	@Test
	void aPrivatelyBoundNodeKeepsTracking() {
		// Behind NAT or an elastic address the endpoint can move - a home router reconnecting - so the
		// node never stops listening for it.
		DHT dht = dht("10.0.0.10", false);
		agreeOn(dht, PUBLIC, 1, 3);
		agreeOn(dht, ELSEWHERE, 1, 3);

		assertEquals(ELSEWHERE, dht.getNodeInfo().getAddress());
	}

	@Test
	void theBoundAddressIsKeptWhileReportersDisagree() {
		DHT dht = dht("10.0.0.10", false);
		for (int i = 1; i <= 5; i++)
			dht.onObservedEndpoint(new InetSocketAddress("64.227.0." + i, 39001),
					new InetSocketAddress("155.138.245.211", 50000 + i));

		assertEquals("10.0.0.10", dht.getNodeInfo().getHost());
		assertTrue(dht.getPublicEndpointHistory().isEmpty());
	}

	@Test
	void aLoopbackBoundNodeInDeveloperModeHandsOutNothing() {
		// Receivers drop loopback candidates even in developer mode, so it would only waste the slot.
		assertTrue(answer(dht("127.0.0.1", true)).isEmpty());
	}
}
