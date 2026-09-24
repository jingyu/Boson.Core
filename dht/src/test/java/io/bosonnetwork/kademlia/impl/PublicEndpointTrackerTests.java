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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.kademlia.impl.PublicEndpointTracker.Outcome;

/**
 * Covers how a node settles on its own public endpoint from what replying nodes report seeing.
 * <p>
 * The scenarios are the ones the design is for: a server behind an elastic or floating address, a home
 * node whose address changes, a NAT that maps a port per destination - and a reporter lying.
 * </p>
 */
public class PublicEndpointTrackerTests {
	private static final long WINDOW = 10 * 60 * 1000;
	private static final long T0 = 1_000_000_000L;

	// Routable addresses throughout: the documentation ranges are bogons, and a bogon is not an answer.
	private static final InetSocketAddress PUBLIC = endpoint("155.138.245.211", 39001);
	private static final InetSocketAddress MOVED = endpoint("45.32.138.246", 39001);

	private static InetSocketAddress endpoint(String host, int port) {
		return new InetSocketAddress(host, port);
	}

	// A reporter from a distinct source: a different /32 each.
	private static InetSocketAddress reporter(int n) {
		return endpoint("64.227.0." + n, 39001);
	}

	private static PublicEndpointTracker tracker() {
		return new PublicEndpointTracker(Network.IPv4, false, 3, WINDOW, 64, 8);
	}

	@Test
	void anEndpointIsBelievedOnlyOnceEnoughReportersAgree() {
		PublicEndpointTracker tracker = tracker();

		assertEquals(Outcome.NONE, tracker.report(reporter(1), PUBLIC, T0));
		assertEquals(Outcome.NONE, tracker.report(reporter(2), PUBLIC, T0));
		assertNull(tracker.current(), "two reporters are not agreement");

		assertEquals(Outcome.CHANGED, tracker.report(reporter(3), PUBLIC, T0));
		assertEquals(PUBLIC, tracker.current());
	}

	@Test
	void oneReporterCountsOnceHoweverOftenItReports() {
		// A liar cannot out-shout honest nodes by repeating itself: only its latest report counts.
		PublicEndpointTracker tracker = tracker();
		for (int i = 0; i < 10; i++)
			tracker.report(reporter(1), MOVED, T0 + i);

		assertNull(tracker.current());
	}

	@Test
	void reportersFromOneSourceCountOnce() {
		// Distinct sources as the routing table counts them: one address, many ports, is one source.
		PublicEndpointTracker tracker = tracker();
		for (int port = 1000; port < 1010; port++)
			tracker.report(endpoint("64.227.0.1", port), MOVED, T0);

		assertNull(tracker.current());
	}

	@Test
	void inDeveloperModeAReporterIsItsAddressAndPort() {
		// Where a whole network runs on one host, as lookup candidates are told apart.
		PublicEndpointTracker tracker = new PublicEndpointTracker(Network.IPv4, true, 3, WINDOW, 64, 8);
		InetSocketAddress lan = endpoint("192.168.8.80", 39001);
		for (int port = 1000; port < 1003; port++)
			tracker.report(endpoint("192.168.8.80", port), lan, T0);

		assertEquals(lan, tracker.current(), "a private address is an answer in developer mode");
	}

	@Test
	void reportsAgeOut() {
		PublicEndpointTracker tracker = tracker();
		tracker.report(reporter(1), PUBLIC, T0);
		tracker.report(reporter(2), PUBLIC, T0);

		assertEquals(Outcome.NONE, tracker.report(reporter(3), PUBLIC, T0 + WINDOW + 1),
				"the first two had expired, so this is one reporter, not three");
		assertNull(tracker.current());
	}

	@Test
	void aMovedEndpointReplacesTheOldOneOnceItOutVotesIt() {
		// The home network case: the router reconnects and comes back with another public address.
		PublicEndpointTracker tracker = tracker();
		for (int i = 1; i <= 3; i++)
			tracker.report(reporter(i), PUBLIC, T0);

		// The same reporters, now seeing the new address: each one's latest report is the one that counts.
		long later = T0 + 60_000;
		assertEquals(Outcome.NONE, tracker.report(reporter(1), MOVED, later));
		assertEquals(Outcome.NONE, tracker.report(reporter(2), MOVED, later), "2 against 1 is not yet agreement");
		assertEquals(Outcome.CHANGED, tracker.report(reporter(3), MOVED, later));
		assertEquals(MOVED, tracker.current());

		List<PublicEndpointTracker.Change> history = tracker.history();
		assertEquals(2, history.size());
		assertNull(history.get(0).from());
		assertEquals(PUBLIC, history.get(0).to());
		assertEquals(PUBLIC, history.get(1).from());
		assertEquals(MOVED, history.get(1).to());
	}

	@Test
	void anAgreedEndpointDoesNotFlapOnATie() {
		// Three honest reporters, then three liars agreeing on another endpoint: a tie does not move it.
		PublicEndpointTracker tracker = tracker();
		for (int i = 1; i <= 3; i++)
			tracker.report(reporter(i), PUBLIC, T0);
		for (int i = 4; i <= 6; i++)
			assertEquals(Outcome.NONE, tracker.report(reporter(i), MOVED, T0));

		assertEquals(PUBLIC, tracker.current());
	}

	@Test
	void anEndpointPeersCouldNotUseIsNoReport() {
		PublicEndpointTracker tracker = tracker();
		List<InetSocketAddress> unusable = List.of(
				endpoint("10.0.0.10", 39001),           // private: what an elastic address maps to
				endpoint("203.0.113.10", 39001),        // documentation range
				endpoint("127.0.0.1", 39001),           // loopback
				endpoint("2001:4860:4860::8888", 39001) // the other family
		);

		for (InetSocketAddress observed : unusable)
			for (int i = 1; i <= 3; i++)
				tracker.report(reporter(i), observed, T0);

		assertNull(tracker.current());
	}

	@Test
	void aNatThatMapsAPortPerDestinationIsReportedOnce() {
		// Every reporter sees the same address with a different port: no endpoint is ever agreed on,
		// which is the truth, and the reason is told once.
		PublicEndpointTracker tracker = tracker();
		assertEquals(Outcome.NONE, tracker.report(reporter(1), endpoint("155.138.245.211", 50001), T0));
		assertEquals(Outcome.NONE, tracker.report(reporter(2), endpoint("155.138.245.211", 50002), T0));
		assertEquals(Outcome.PORTS_DISAGREE, tracker.report(reporter(3), endpoint("155.138.245.211", 50003), T0));
		assertEquals(Outcome.NONE, tracker.report(reporter(4), endpoint("155.138.245.211", 50004), T0));

		assertNull(tracker.current());
	}

	@Test
	void clearingTheReportsKeepsWhatWasAgreed() {
		PublicEndpointTracker tracker = tracker();
		for (int i = 1; i <= 3; i++)
			tracker.report(reporter(i), PUBLIC, T0);
		tracker.report(reporter(4), MOVED, T0);
		tracker.report(reporter(5), MOVED, T0);

		tracker.clearReports();
		assertEquals(PUBLIC, tracker.current());
		assertEquals(1, tracker.history().size());

		// The two reports for MOVED are gone: one more is one reporter, not three.
		assertEquals(Outcome.NONE, tracker.report(reporter(6), MOVED, T0));
		assertEquals(PUBLIC, tracker.current());
	}

	@Test
	void theStateItHoldsIsBounded() {
		PublicEndpointTracker tracker = new PublicEndpointTracker(Network.IPv4, false, 3, WINDOW, 4, 2);
		// Many reporters: only the latest four are held, so the first three no longer count...
		for (int i = 1; i <= 3; i++)
			tracker.report(reporter(i), PUBLIC, T0);
		for (int i = 10; i <= 13; i++)
			tracker.report(reporter(i), endpoint("64.227.1." + i, 39001), T0);

		// ...and history keeps only the last changes.
		for (int round = 0; round < 3; round++) {
			InetSocketAddress next = endpoint("155.138.245." + (100 + round), 39001);
			for (int i = 20; i <= 23; i++)
				tracker.report(reporter(i), next, T0 + round);
		}
		assertEquals(2, tracker.history().size());
		assertEquals(endpoint("155.138.245.102", 39001), tracker.current());
	}
}
