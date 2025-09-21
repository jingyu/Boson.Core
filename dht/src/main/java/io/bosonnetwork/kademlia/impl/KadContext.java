package io.bosonnetwork.kademlia.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.Network;
import io.bosonnetwork.kademlia.utils.Timer;

public class KadContext implements Timer, Executor {
	private final Vertx vertx;
	private final Context vertxContext;
	private final Identity identity;
	private final Network network;
	private final DHT dht;
	private final boolean developerMode;
	private DHT sibling;

	public KadContext(Vertx vertx, Context vertxContext, Identity identity, Network network, DHT dht, boolean developerMode) {
		this.vertx = vertx;
		this.vertxContext = vertxContext;
		this.identity = identity;
		this.network = network;
		this.dht = dht;
		this.developerMode = developerMode;
	}

	public KadContext(Vertx vertx, Context vertxContext, Identity identity, Network network, DHT dht) {
		this(vertx, vertxContext, identity, network, dht, false);
	}

	public Vertx getVertx() {
		return vertx;
	}

	public Context getVertxContext() {
		return vertxContext;
	}

	public Id getLocalId() {
		return identity.getId();
	}

	public boolean isLocalId(Id id) {
		return id.equals(identity.getId());
	}

	public Identity getIdentity() {
		return identity;
	}

	public Network getNetwork() {
		return network;
	}

	public DHT getDHT() {
		return dht;
	}

	public boolean hasSibling() {
		return sibling != null;
	}

	public DHT getSibling() {
		return sibling;
	}

	protected void setSibling(DHT dht) {
		this.sibling = dht;
	}

	public boolean isDeveloperMode() {
		return developerMode;
	}

	public void runOnContext(Consumer<Void> action) {
		vertxContext.runOnContext(action::accept);
	}

	public void runOnContext(Runnable action) {
		vertxContext.runOnContext(unused -> action.run());
	}

	public <T> Future<T> executeBlocking(Callable<T> handler) {
		return vertx.executeBlocking(handler);
	}

	@Override
	public long setPeriodic(long initialDelay, long delay, Consumer<Long> handler) {
		return vertx.setPeriodic(initialDelay, delay, handler::accept);
	}

	@Override
	public long setPeriodic(long delay, Consumer<Long> handler) {
		return vertx.setPeriodic(delay, handler::accept);
	}

	@Override
	public long setTimer(long delay, Consumer<Long> handler) {
		return vertx.setTimer(delay, handler::accept);
	}

	@Override
	public boolean cancelTimer(long timerId) {
		return vertx.cancelTimer(timerId);
	}

	@Override
	public void execute(Runnable command) {
		vertxContext.runOnContext(unused -> command.run());
	}
}