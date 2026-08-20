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

package io.bosonnetwork;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What a publish achieved, node by node.
 * <p>
 * {@link Node#storeValue} and {@link Node#announcePeer} write to the nodes closest to the payload's id, and
 * this reports how each of them answered. It is deliberately not a single bit: in a network of nodes nobody
 * vouches for, one peer's answer is evidence and not a verdict, so the decision of what a given set of
 * answers means belongs to the caller rather than to the DHT.
 * </p>
 * <p>
 * <b>Every entry here is a claim made by a peer.</b> The {@code cause} attached to a refusal is whatever
 * that node chose to say. A single node claiming your sequence number is stale may be telling the truth,
 * may hold corrupt state, or may simply be lying to keep your value off the network - and nothing in the
 * protocol can tell those apart. Weigh the set, not a member of it.
 * </p>
 * <p>
 * <b>The local copy is not represented here and is not affected by any of it.</b> Both operations write to
 * this node's own storage first and publish afterwards, so a result exists only where the local write
 * already succeeded - counting it would make every predicate on this class trivially true. That also means
 * {@link Status#FAILED} does not mean the payload is lost: it is held locally and served from there, and a
 * persistent one is re-announced on a timer. What it does mean is that nothing will <em>find</em> it, since
 * a search converges on the nodes closest to the payload's id and this node is not one of them.
 * </p>
 * <p>
 * <b>Only {@link Status#FAILED} is reported to the caller as an error</b> - see {@link #isFailure()}. A
 * publish that found no node to ask completes, because a node that is still bootstrapping reaches that
 * routinely and it is not something a caller can act on. The consequence is worth knowing: <b>a
 * non-persistent publish on a node with no peers completes having published nothing, and is never retried.
 * </b> The payload is in local storage and nowhere else. A caller that needs to know the network took it
 * must check {@link #isAnnounced()} rather than only awaiting the future - awaiting tells you the operation
 * did not go wrong, which is not the same question.
 * </p>
 */
public final class AnnounceResult {
	/** How the publish went overall. */
	public enum Status {
		/** Every node that was written to acknowledged the write. */
		SUCCESS,
		/**
		 * Some nodes took it and some did not.
		 * <p>
		 * Not routine. Every target answered a lookup moments earlier and handed out a write token good
		 * for minutes, so a target that then fails has changed its mind or gone away in the interval -
		 * worth noticing, while leaving the payload retrievable from the nodes that did take it.
		 * </p>
		 */
		PARTIAL_SUCCESS,
		/**
		 * There was no node to write to: the lookup found none.
		 * <p>
		 * Distinct from {@link #FAILED} because nothing was refused - nothing was asked. A node still
		 * bootstrapping, or one whose network really does contain only itself, reaches this routinely,
		 * and it says nothing about whether the payload would be accepted. It is therefore <b>not</b>
		 * reported as an error: the payload is held locally, and a persistent one is offered again at
		 * the next re-announce cycle, unchanged and undelayed.
		 * </p>
		 * <p>
		 * On a node that has finished bootstrapping and has peers, this is abnormal and worth
		 * investigating even though it is not an error.
		 * </p>
		 */
		NO_TARGETS,
		/**
		 * Nodes were asked and none accepted.
		 * <p>
		 * The only status reported to the caller as a failure, because it is the only one where the
		 * network was in a position to take the payload and did not.
		 * </p>
		 */
		FAILED
	}

	/** How one node answered. */
	public enum Outcome {
		/** The node stored the payload. */
		ACKNOWLEDGED,
		/** The node answered with an error. Its {@code cause} says what it claimed. */
		REFUSED,
		/** The node never answered. */
		TIMED_OUT,
		/** The node was never written to, having supplied no token to write with. */
		NOT_SENT
	}

	/**
	 * Why a node did not take the payload.
	 * <p>
	 * <b>For a refusal this is the remote node's word, not a finding.</b> The code and the message are
	 * whatever that node chose to send, reproduced here unexamined - see the note on
	 * {@link AnnounceResult}. Anything acted on it should be weighed across the whole target set, and a
	 * message must not be treated as trustworthy text: it crossed the network from a node nobody vouches
	 * for.
	 * </p>
	 * <p>
	 * The code is the numeric DHT error code, which is what the wire actually carried - a refusal arrives
	 * as a code and a message, so nothing is lost or invented by keeping it that way. Codes also describe
	 * failures that never left this node, distinguished by the {@link Outcome} rather than by the code:
	 * {@link Outcome#REFUSED} is what a peer said, {@link Outcome#NOT_SENT} is what happened here.
	 * </p>
	 * <p>
	 * A cause is present only where there is something to say. A node that never answered, one whose call
	 * was cancelled, and one that supplied no write token all carry none: their outcome is the whole of
	 * the explanation.
	 * </p>
	 *
	 * @param code    the DHT error code.
	 * @param message the accompanying detail, or null if there was none.
	 */
	public record Cause(int code, @Nullable String message) {
		@Override
		public String toString() {
			return message == null ? String.valueOf(code) : code + ": " + message;
		}
	}

	/**
	 * One node's answer.
	 *
	 * @param nodeId  the node that was written to.
	 * @param outcome how it answered.
	 * @param cause   why it did not take the payload, or null if there is nothing to say - always null
	 *                for {@link Outcome#ACKNOWLEDGED}. A claim, not a finding - see {@link Cause}.
	 */
	public record Target(Id nodeId, Outcome outcome, @Nullable Cause cause) {
		/**
		 * Whether this node stored the payload.
		 *
		 * @return true if acknowledged.
		 */
		public boolean isAcknowledged() {
			return outcome == Outcome.ACKNOWLEDGED;
		}
	}

	private final List<Target> targets;
	private final int acknowledged;

	private AnnounceResult(List<Target> targets) {
		this.targets = List.copyOf(targets);
		this.acknowledged = (int) this.targets.stream().filter(Target::isAcknowledged).count();
	}

	/**
	 * Builds a result from the answers a publish collected.
	 *
	 * @param targets one entry per node the publish considered, in any order.
	 * @return the result.
	 */
	public static AnnounceResult of(Collection<Target> targets) {
		return new AnnounceResult(List.copyOf(targets));
	}

	/**
	 * Combines the results of publishing over two address families.
	 * <p>
	 * The families are separate networks with separate routing tables, so they reach different nodes and
	 * the union is what the caller asked for. The aggregate is recomputed over that union rather than
	 * combined from the two summaries, which is what makes "one family reached nobody" a partial success
	 * instead of a failure.
	 * </p>
	 *
	 * @param a one family's result.
	 * @param b the other family's result.
	 * @return the combined result.
	 */
	public static AnnounceResult merge(AnnounceResult a, AnnounceResult b) {
		List<Target> combined = new ArrayList<>(a.targets.size() + b.targets.size());
		combined.addAll(a.targets);
		combined.addAll(b.targets);
		return new AnnounceResult(combined);
	}

	/**
	 * How the publish went overall.
	 *
	 * @return the status.
	 */
	public Status status() {
		if (targets.isEmpty())
			return Status.NO_TARGETS;

		if (acknowledged == 0)
			return Status.FAILED;

		return acknowledged == targets.size() ? Status.SUCCESS : Status.PARTIAL_SUCCESS;
	}

	/**
	 * Whether this outcome is reported to the caller as an error.
	 * <p>
	 * True only for {@link Status#FAILED}. The rule lives here rather than at the two places that
	 * settle a caller's future, so that "which outcomes are errors" cannot come to mean two things.
	 * </p>
	 * <p>
	 * Note that this is not the negation of {@link #isAnnounced()}, and the gap between them is the
	 * point: a publish that found nobody to ask reached no node and is still not an error.
	 * </p>
	 *
	 * @return true if nodes were asked and none accepted.
	 */
	public boolean isFailure() {
		return status() == Status.FAILED;
	}

	/**
	 * Every node the publish considered, including those it never wrote to.
	 *
	 * @return the per-node answers, unmodifiable.
	 */
	public List<Target> targets() {
		return targets;
	}

	/**
	 * How many nodes stored the payload.
	 *
	 * @return the acknowledgement count.
	 */
	public int acknowledged() {
		return acknowledged;
	}

	/**
	 * Whether any node on the network took the payload.
	 * <p>
	 * The question most callers are asking, and the reason it is not called {@code isStored}: this node's
	 * own copy is written before the publish begins and is not counted here, so "stored" would be true of
	 * every result ever produced and would answer nothing.
	 * </p>
	 *
	 * @return true if at least one node acknowledged.
	 */
	public boolean isAnnounced() {
		return acknowledged > 0;
	}

	/**
	 * The refusal to report when every node that refused said the same thing.
	 * <p>
	 * Offered so that the network's agreement can still be surfaced as one typed exception - letting a
	 * caller keep catching the refusal it cares about - without any single node being able to produce
	 * that outcome by itself. Agreement is on the code alone: the message is free text each node writes
	 * for itself, and requiring it to match would mean one node's phrasing decided whether the network
	 * agreed.
	 * </p>
	 * <p>
	 * With one target, "every node that refused" is one node's word. {@link #targets()} is where to
	 * check how many actually agreed.
	 * </p>
	 *
	 * @return the shared cause, or null if no node refused or they refused for different reasons.
	 */
	public @Nullable Cause unanimousRefusal() {
		Cause first = null;
		for (Target target : targets) {
			if (target.outcome() != Outcome.REFUSED)
				continue;

			Cause cause = target.cause();
			if (cause == null)
				return null;

			if (first == null)
				first = cause;
			else if (first.code() != cause.code())
				return null;
		}

		return first;
	}

	@Override
	public String toString() {
		return "AnnounceResult{" + status() + ", " + acknowledged + " of " + targets.size() +
				" acknowledged, targets=" + targets + "}";
	}
}
