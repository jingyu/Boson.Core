package io.bosonnetwork.kademlia.impl;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.kademlia.utils.Timer;

/**
 * The ambient per-DHT context handed to the RPC server, the task manager and every task.
 * <p>
 * This class holds no state of its own: every accessor delegates to the owning {@link DHT}, which is
 * the single owner of the effective Kademlia configuration and of the Vert.x handles. Reading through
 * the DHT rather than caching means a value can never go stale - the Vert.x context in particular is
 * only assigned when the verticle is deployed, which is after this object is constructed.
 * </p>
 * <p>
 * Reason the Kademlia parameters are reachable from here at all: a
 * {@link io.bosonnetwork.kademlia.tasks.Task} holds a context but no reference to its task manager, so
 * the context is the one object every task can read {@code k} and {@code alpha} from.
 * </p>
 */
public class KadContext implements Timer, Executor {
	// Null only for the no-DHT constructor below; see its contract.
	private final @Nullable DHT dht;

	/**
	 * Creates a context backed by the given DHT.
	 *
	 * @param dht the owning DHT, must not be null.
	 */
	public KadContext(DHT dht) {
		this.dht = Objects.requireNonNull(dht, "dht");
	}

	/**
	 * Creates a context with no owning DHT, for subclasses that supply every value themselves.
	 * <p>
	 * <b>Contract:</b> because every accessor on this class delegates to the DHT, a subclass using this
	 * constructor must override every accessor it uses. Any it fails to override throws
	 * {@link NullPointerException} on first use - loudly, rather than silently returning a wrong value.
	 * The one exception is {@link #getSibling()}, deliberately null-tolerant so that a DHT-less context
	 * reports "no sibling" rather than failing.
	 * </p>
	 * <p>
	 * This exists for test doubles; production code uses {@link #KadContext(DHT)}.
	 * </p>
	 */
	protected KadContext() {
		this.dht = null;
	}

	/**
	 * Returns the owning DHT, failing with a message that names the actual mistake.
	 * <p>
	 * A subclass built on {@link #KadContext()} reaches this only by failing to override an accessor,
	 * so a bare NPE would point at the delegation rather than at the missing override.
	 * </p>
	 *
	 * @return the owning DHT, never null.
	 */
	private DHT requireDht() {
		if (dht == null)
			throw new NullPointerException(getClass().getSimpleName()
					+ " has no owning DHT: a KadContext created without one must override every accessor");

		return dht;
	}

	/**
	 * Returns the owning DHT.
	 * <p>
	 * Deliberately not nullable: production call sites dereference this directly, and weakening the
	 * contract for the benefit of test doubles would push null handling into all of them. A context
	 * created without a DHT throws here instead.
	 * </p>
	 *
	 * @return the owning DHT, never null.
	 */
	public DHT getDHT() {
		return requireDht();
	}

	public Vertx getVertx() {
		return requireDht().getVertx();
	}

	public Context getVertxContext() {
		return requireDht().vertxContext();
	}

	public Identity getIdentity() {
		return requireDht().getIdentity();
	}

	public Id getLocalId() {
		return getIdentity().getId();
	}

	public boolean isLocalId(Id id) {
		return id.equals(getIdentity().getId());
	}

	public Network getNetwork() {
		return requireDht().getNetwork();
	}

	public boolean hasSibling() {
		return getSibling() != null;
	}

	public @Nullable DHT getSibling() {
		// Always read through the owning DHT, the sibling can be wired and unwired during deployment.
		// Null-tolerant on purpose: a DHT-less context simply has no sibling.
		return dht != null ? dht.getSibling() : null;
	}

	/**
	 * Returns the Kademlia concurrency parameter (alpha): how many RPCs a task keeps in flight.
	 *
	 * @return the concurrency parameter.
	 */
	public int getAlpha() {
		return requireDht().getAlpha();
	}

	/**
	 * Returns the Kademlia bucket size (k).
	 *
	 * @return the bucket size.
	 */
	public int getK() {
		return requireDht().getK();
	}

	/**
	 * Returns the per-bucket replacement cache size.
	 *
	 * @return the replacement cache size.
	 */
	public int getReplacements() {
		return requireDht().getReplacements();
	}

	/**
	 * Returns the ceiling on concurrently running tasks; further tasks are queued.
	 *
	 * @return the concurrent task ceiling.
	 */
	public int getConcurrentTasks() {
		return requireDht().getConcurrentTasks();
	}

	/**
	 * Returns the concurrency ceiling for low-priority (background maintenance) tasks: 2 whenever
	 * alpha allows it, otherwise 1.
	 * <p>
	 * Derived from alpha rather than configured separately, and deliberately capped rather than
	 * scaling with it: background maintenance should not grow more parallel just because foreground
	 * lookups do. At the default alpha of 3 this yields 2, the value this module used before the
	 * parameter became configurable. Only at alpha 1 or 2 does it fall back to 1, where it no longer
	 * sits strictly below alpha - unavoidable at that end of the range.
	 * </p>
	 *
	 * @return the low-priority concurrency parameter, at least 1.
	 */
	public int getLowPriorityAlpha() {
		return Math.max(1, Math.min(KadConstants.LOW_PRIORITY_ALPHA, getAlpha() - 1));
	}

	public boolean isDeveloperMode() {
		return requireDht().isDeveloperMode();
	}

	public void runOnContext(Consumer<Void> action) {
		getVertxContext().runOnContext(action::accept);
	}

	public void runOnContext(Runnable action) {
		runOnContext(unused -> action.run());
	}

	public <T> Future<T> executeBlocking(Callable<T> handler) {
		return getVertxContext().executeBlocking(handler);
	}

	public <T> Future<T> executeBlocking(Callable<T> handler, boolean ordered) {
		return getVertxContext().executeBlocking(handler, ordered);
	}

	@Override
	public long setPeriodic(long initialDelay, long delay, Consumer<Long> handler) {
		return getVertx().setPeriodic(initialDelay, delay, handler::accept);
	}

	@Override
	public long setPeriodic(long delay, Consumer<Long> handler) {
		return getVertx().setPeriodic(delay, handler::accept);
	}

	@Override
	public long setTimer(long delay, Consumer<Long> handler) {
		return getVertx().setTimer(delay, handler::accept);
	}

	@Override
	public boolean cancelTimer(long timerId) {
		return getVertx().cancelTimer(timerId);
	}

	@Override
	public void execute(Runnable command) {
		runOnContext(unused -> command.run());
	}
}