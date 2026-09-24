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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.kademlia.security.SourceKey;
import io.bosonnetwork.utils.AddressUtils;

/**
 * Works out a DHT node's own public endpoint from what the nodes it talks to report seeing.
 * <p>
 * A node cannot learn its public endpoint from its own socket: behind NAT, or on a cloud host with an
 * elastic or floating address, the address it binds is not the one the Internet reaches it by - and
 * behind NAT the port is mapped too. What it can learn is what others see, and every reply it gets
 * says so: the replying node puts the address the request arrived from in the reply.
 * </p>
 * <p>
 * No single report is believed. Nodes are strangers, and one of them can report anything - an address
 * it controls, say, to be handed out as ours. An endpoint is taken as ours once
 * {@link KadConstants#PUBLIC_ENDPOINT_MIN_REPORTERS} reporters from distinct sources agree on it, each
 * reporter counting with its latest report only, and reports older than
 * {@link KadConstants#PUBLIC_ENDPOINT_VOTE_WINDOW} not at all. A new endpoint replaces the current one
 * when it is agreed on and has more reporters than the current one: the latest agreement wins, and one
 * stray report cannot make it flap.
 * </p>
 * <p>
 * An agreed endpoint is also given up when it goes stale against evidence: none of its reports is left in
 * the window, while at least as many reporters as agreement takes have reported something else. That is
 * a NAT that has changed behaviour under a running node - replaced, or now mapping a port per
 * destination - and the endpoint it gave out no longer works. Silence alone is not evidence: a node that
 * hears no reports at all keeps its endpoint.
 * </p>
 * <p>
 * A NAT that maps a different port for every destination shows up as reporters agreeing on the address
 * but not on the port. No endpoint is ever agreed on then, which is the truth - such a node cannot be
 * reached by a node it has not contacted first - and {@link #report} says so once, so it can be logged.
 * </p>
 * <p>
 * Not thread-safe: owned by one DHT and used only on its context.
 * </p>
 */
final class PublicEndpointTracker {
	/** What a report changed. */
	enum Outcome {
		/** Nothing worth telling. */
		NONE,
		/** A new public endpoint was agreed on; {@link #current()} returns it. */
		CHANGED,
		/** The agreed endpoint went stale against reporters seeing something else; there is none now. */
		LOST,
		/** Reporters agree on the address but not on the port - reported once. */
		PORTS_DISAGREE
	}

	/**
	 * One change of public endpoint.
	 *
	 * @param from the endpoint before, or {@code null} if there was none.
	 * @param to   the endpoint after, or {@code null} if it was lost.
	 * @param at   when it changed, in epoch milliseconds.
	 */
	record Change(@Nullable InetSocketAddress from, @Nullable InetSocketAddress to, long at) { }

	private record Report(InetSocketAddress endpoint, long at) { }

	private final Network network;
	private final boolean developerMode;
	private final int minReporters;
	private final long window;
	private final int maxReporters;
	private final int historySize;

	// Each reporter's latest report, keyed by reporter and ordered least recent first: a report is
	// removed and re-inserted, so the head is always the oldest and expiry stops at the first fresh one.
	private final LinkedHashMap<Object, Report> reports = new LinkedHashMap<>();
	private final ArrayDeque<Change> history = new ArrayDeque<>();
	private @Nullable InetSocketAddress current;
	private boolean portsDisagreeReported;

	/**
	 * Creates a tracker with the protocol's parameters.
	 *
	 * @param network       the address family of the DHT that owns it.
	 * @param developerMode whether the DHT runs in developer mode.
	 */
	PublicEndpointTracker(Network network, boolean developerMode) {
		this(network, developerMode, KadConstants.PUBLIC_ENDPOINT_MIN_REPORTERS,
				KadConstants.PUBLIC_ENDPOINT_VOTE_WINDOW, KadConstants.PUBLIC_ENDPOINT_MAX_REPORTERS,
				KadConstants.PUBLIC_ENDPOINT_HISTORY);
	}

	/**
	 * Creates a tracker with explicit parameters, for tests.
	 *
	 * @param network       the address family of the DHT that owns it.
	 * @param developerMode whether the DHT runs in developer mode.
	 * @param minReporters  how many reporters must agree.
	 * @param window        how long a report counts, in milliseconds.
	 * @param maxReporters  how many reporters are remembered.
	 * @param historySize   how many changes are remembered.
	 */
	PublicEndpointTracker(Network network, boolean developerMode, int minReporters, long window,
			int maxReporters, int historySize) {
		this.network = network;
		this.developerMode = developerMode;
		this.minReporters = minReporters;
		this.window = window;
		this.maxReporters = maxReporters;
		this.historySize = historySize;
	}

	/**
	 * Takes one reporter's observation of our endpoint.
	 * <p>
	 * The caller vouches for the reporter: it must be the node that answered a request of ours, from the
	 * address the request went to. That is what makes the report its own rather than one aimed at us by
	 * a third party.
	 * </p>
	 *
	 * @param reporter the address of the node that replied.
	 * @param observed the endpoint it says our request came from.
	 * @param now      the current time, in epoch milliseconds.
	 * @return what the report changed.
	 */
	Outcome report(InetSocketAddress reporter, InetSocketAddress observed, long now) {
		if (!isEligible(observed))
			return Outcome.NONE;

		// Distinct sources, as the routing table counts them. In developer mode a whole network can run
		// on one host, so there - as for lookup candidates - a reporter is its address and port.
		Object key = developerMode ? reporter : SourceKey.of(reporter.getAddress());
		reports.remove(key);
		reports.put(key, new Report(observed, now));
		while (reports.size() > maxReporters)
			reports.remove(reports.keySet().iterator().next());

		expire(now);
		return evaluate(now);
	}

	/**
	 * Returns the agreed public endpoint.
	 *
	 * @return the endpoint, or {@code null} while none has been agreed on.
	 */
	@Nullable InetSocketAddress current() {
		return current;
	}

	/**
	 * Returns the past changes of public endpoint, oldest first. For logs and diagnostics only.
	 *
	 * @return the changes.
	 */
	List<Change> history() {
		return new ArrayList<>(history);
	}

	// The same address rule as for lookup candidates: an endpoint peers could not use is no endpoint.
	private boolean isEligible(InetSocketAddress observed) {
		InetAddress addr = observed.getAddress();
		if (addr == null || observed.getPort() <= 0 || !network.canUseAddress(addr))
			return false;

		return developerMode ? AddressUtils.isAnyUnicast(addr) : AddressUtils.isGlobalUnicast(addr);
	}

	private void expire(long now) {
		Iterator<Report> it = reports.values().iterator();
		while (it.hasNext()) {
			if (now - it.next().at() <= window)
				break;

			it.remove();
		}
	}

	private Outcome evaluate(long now) {
		Map<InetSocketAddress, Integer> byEndpoint = new HashMap<>();
		Map<InetAddress, Integer> byAddress = new HashMap<>();
		for (Report report : reports.values()) {
			byEndpoint.merge(report.endpoint(), 1, Integer::sum);
			byAddress.merge(report.endpoint().getAddress(), 1, Integer::sum);
		}

		InetSocketAddress leader = null;
		int leaderCount = 0;
		for (Map.Entry<InetSocketAddress, Integer> entry : byEndpoint.entrySet()) {
			if (entry.getValue() > leaderCount) {
				leader = entry.getKey();
				leaderCount = entry.getValue();
			}
		}

		int currentCount = current == null ? 0 : byEndpoint.getOrDefault(current, 0);
		if (leader != null && leaderCount >= minReporters && !leader.equals(current) && leaderCount > currentCount) {
			record(leader, now);
			return Outcome.CHANGED;
		}

		// Stale against evidence: not one fresh report for the agreed endpoint, and enough reporters to have
		// agreed on it all saying otherwise - just not on any one endpoint, or the branch above would have
		// taken it. Every report left is fresh after expire(), and none is for the current endpoint, so the
		// map's size is the count of reporters seeing something else. The port check below gets its turn on
		// the next report, now that there is no current endpoint to hide it.
		if (current != null && currentCount == 0 && reports.size() >= minReporters) {
			record(null, now);
			return Outcome.LOST;
		}

		// Enough reporters for agreement, all on one address, and still none on one endpoint: the port is
		// what differs, which is a NAT mapping per destination.
		if (current == null && !portsDisagreeReported && leaderCount < minReporters) {
			for (int count : byAddress.values()) {
				if (count >= minReporters) {
					portsDisagreeReported = true;
					return Outcome.PORTS_DISAGREE;
				}
			}
		}

		return Outcome.NONE;
	}

	private void record(@Nullable InetSocketAddress endpoint, long now) {
		history.addLast(new Change(current, endpoint, now));
		while (history.size() > historySize)
			history.removeFirst();

		current = endpoint;
		portsDisagreeReported = false;
	}

	/**
	 * Drops the reports held, keeping the agreed endpoint and the history of changes.
	 * <p>
	 * For a DHT that has stopped tracking: the reports are the only state that grows, and the rest is
	 * still true - and still what a diagnostic would want to see.
	 * </p>
	 */
	void clearReports() {
		reports.clear();
		portsDisagreeReported = false;
	}
}
