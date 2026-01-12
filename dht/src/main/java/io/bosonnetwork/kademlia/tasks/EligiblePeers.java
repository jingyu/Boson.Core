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

package io.bosonnetwork.kademlia.tasks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;

/**
 * This class maintains a bounded, prioritized set of peers eligible for a Kademlia task.
 * Peers are filtered by target ID and minimum sequence number.
 * Peers are accumulated via add() and pruned explicitly via prune().
 * Capacity enforcement happens only when prune() is invoked.
 * Ordering priority: sequence number (descending), authentication status,
 * XOR distance for authenticated peers.
 * Unauthenticated peers are intentionally unordered and may be pruned arbitrarily to preserve decentralization.
 */
public class EligiblePeers {
	/**
	 * The lookup target used for XOR distance ordering.
	 */
	private final Id target;
	/**
	 * The minimum acceptable sequence number.
	 */
	private final int expectedSequenceNumber;
	/**
	 * The maximum number of peers to retain.
	 */
	private final int expectedCount;

	/**
	 * Stores deduplicated eligible peers keyed by peerId:fingerprint.
	 */
	private final Map<String, PeerInfo> eligible;

	/**
	 * Constructs an EligiblePeers instance with the given target, minimum sequence number,
	 * and maximum expected count.
	 *
	 * @param target the lookup target used for XOR distance ordering
	 * @param expectedSequenceNumber the minimum acceptable sequence number for peers
	 * @param expectedCount the maximum number of peers to retain
	 */
	public EligiblePeers(Id target, int expectedSequenceNumber, int expectedCount) {
		this.target = target;
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.expectedCount = expectedCount;
		this.eligible = new HashMap<>();
	}

	/**
	 * Returns the current number of eligible peers.
	 *
	 * @return the number of eligible peers stored
	 */
	public int size() {
		return eligible.size();
	}

	/**
	 * Checks whether there are no eligible peers.
	 *
	 * @return true if no eligible peers are stored, false otherwise
	 */
	public boolean isEmpty() {
		return eligible.isEmpty();
	}

	/**
	 * Checks whether the number of eligible peers has reached or exceeded
	 * the maximum expected count. This method does not perform pruning.
	 *
	 * @return true if the current size of eligible peers is greater than or
	 *         equal to the expected count, false otherwise
	 */
	public boolean reachedCapacity() {
		return eligible.size() >= expectedCount;
	}

	/**
	 * Adds a collection of peers to the eligible set using an atomic
	 * pre-validation and merge process.
	 * <p>
	 * All peers in the collection are validated first. If any peer is invalid
	 * (target ID mismatch, sequence number below the expected minimum when enabled,
	 * or {@link PeerInfo#isValid()} returns false), the method returns {@code false}
	 * and no peers are added.
	 * <p>
	 * Only if all peers pass validation will they be merged into the eligible set.
	 * <p>
	 * Deduplication logic:
	 * <ul>
	 *   <li>Peers are keyed by their {@code peerId:fingerprint} combination.</li>
	 *   <li>If a peer with the same key already exists, the peer with the higher
	 *       sequence number is retained.</li>
	 * </ul>
	 * <p>
	 * This method does <strong>not</strong> enforce capacity limits.
	 * Pruning must be triggered explicitly via {@link #prune()}.
	 *
	 * @param peers the collection of peers to add
	 * @return {@code true} if all peers are valid, and the merge succeeds;
	 *         {@code false} if any peer is invalid and the operation is aborted
	 */
	public boolean add(Collection<PeerInfo> peers) {
		// check first, should drop the result on any ineligible peer
		for (PeerInfo p : peers) {
			if (!p.getId().equals(target) ||
					(expectedSequenceNumber >= 0 && p.getSequenceNumber() < expectedSequenceNumber) ||
					!p.isValid())
				return false;
		}

		peers.forEach(p -> {
			if (!p.getId().equals(target) || p.getSequenceNumber() < expectedSequenceNumber)
				return;

			String key = p.getId().toString() + ":" + p.getFingerprint();
			eligible.compute(key, (k, v) ->
					v == null || v.getSequenceNumber() < p.getSequenceNumber() ? p : v);
		});

		return true;
	}

	/**
	 * Enforces the expectedCount limit by pruning excess peers.
	 * <p>
	 * Ordering rules for pruning:
	 * - Peers are ordered by sequence number (descending), authentication status,
	 *   and XOR distance for authenticated peers.
	 * <p>
	 * Unauthenticated peers may be removed arbitrarily to preserve decentralization,
	 * as they are intentionally unordered.
	 */
	public void prune() {
		if (reachedCapacity()) {
			List<PeerInfo> toRemove = eligible.values().stream()
					.sorted(this::peerOrder)
					.skip(expectedCount)
					.toList();

			for (PeerInfo p : toRemove)
				eligible.remove(p.getId().toString() + ":" + p.getFingerprint());
		}
	}

	/**
	 * Compares two peers to determine their ordering priority.
	 * Ordering rules:
	 * 1. Sequence number in descending order (higher first).
	 * 2. Authentication status (authenticated peers before unauthenticated).
	 * 3. For authenticated peers, XOR distance to the target is used as a tiebreaker.
	 * <p>
	 * Unauthenticated peers compare as equal and are thus unordered relative to each other.
	 *
	 * @param p1 the first peer to compare
	 * @param p2 the second peer to compare
	 * @return negative if p1 < p2, positive if p1 > p2, zero if equal
	 */
	private int peerOrder(PeerInfo p1, PeerInfo p2) {
		int diff = Integer.compare(p2.getSequenceNumber(), p1.getSequenceNumber());
		if (diff != 0)
			return diff;

		diff = Boolean.compare(p2.isAuthenticated(), p1.isAuthenticated());
		if (diff != 0)
			return diff;

		// Kademlia XOR distance
		if (p1.isAuthenticated() && p2.isAuthenticated())
			return target.threeWayCompare(p1.getNodeId(), p2.getNodeId());

		return 0;
	}

	/**
	 * Returns a list of eligible peers ordered by sequence number (descending),
	 * authentication status, and XOR distance for authenticated peers.
	 *
	 * @return the ordered list of eligible peers
	 */
	public List<PeerInfo> getPeers() {
		if (eligible.isEmpty())
			return List.of();

		List<PeerInfo> peers = new ArrayList<>(eligible.values());
		peers.sort(this::peerOrder);
		return peers;
	}
}