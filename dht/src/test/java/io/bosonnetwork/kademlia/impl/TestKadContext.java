package io.bosonnetwork.kademlia.impl;

import java.util.Objects;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.jspecify.annotations.NonNull;

import io.bosonnetwork.Identity;
import io.bosonnetwork.kademlia.security.SuspiciousNodeDetector;

public class TestKadContext extends KadContext {
	private final Context vertxContext;
	private final Identity identity;
	private final Network network;
	private SuspiciousNodeDetector suspiciousNodeDetector;
	private int alpha;
	private int k;
	private int replacements;
	private int concurrentTasks;
	private boolean developerMode;

	public TestKadContext(Context vertxContext, Identity identity, Network network) {
		// No owning DHT: every accessor this test double relies on is overridden below.
		super();
		this.vertxContext = vertxContext;
		this.identity = identity;
		this.network = network;
		this.suspiciousNodeDetector = SuspiciousNodeDetector.disabled();
		this.alpha = KadConstants.ALPHA;
		this.k = KadConstants.K;
		this.replacements = KadConstants.REPLACEMENTS;
		this.concurrentTasks = KadConstants.CONCURRENT_TASKS;
		this.developerMode = true;
	}

	public SuspiciousNodeDetector getSuspiciousNodeDetector() {
		return suspiciousNodeDetector;
	}

	public TestKadContext setSuspiciousNodeDetector(@NonNull SuspiciousNodeDetector suspiciousNodeDetector) {
		this.suspiciousNodeDetector = Objects.requireNonNull(suspiciousNodeDetector);
		return this;
	}

	public TestKadContext setAlpha(int alpha) {
		this.alpha = alpha;
		return this;
	}

	public TestKadContext setK(int k) {
		this.k = k;
		return this;
	}

	public TestKadContext setReplacements(int replacements) {
		this.replacements = replacements;
		return this;
	}

	public TestKadContext setConcurrentTasks(int concurrentTasks) {
		this.concurrentTasks = concurrentTasks;
		return this;
	}

	public TestKadContext setDeveloperMode(boolean developerMode) {
		this.developerMode = developerMode;
		return this;
	}

	@Override
	public Vertx getVertx() {
		return vertxContext.owner();
	}

	@Override
	public Context getVertxContext() {
		return vertxContext;
	}

	@Override
	public Identity getIdentity() {
		return identity;
	}

	@Override
	public Network getNetwork() {
		return network;
	}

	@Override
	public int getAlpha() {
		return alpha;
	}

	@Override
	public int getK() {
		return k;
	}

	@Override
	public int getReplacements() {
		return replacements;
	}

	@Override
	public int getConcurrentTasks() {
		return concurrentTasks;
	}

	@Override
	public boolean isDeveloperMode() {
		return developerMode;
	}
}
