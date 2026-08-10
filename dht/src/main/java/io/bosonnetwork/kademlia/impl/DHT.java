package io.bosonnetwork.kademlia.impl;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.ConnectionStatus;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.InvalidPeerException;
import io.bosonnetwork.kademlia.exceptions.InvalidTokenException;
import io.bosonnetwork.kademlia.exceptions.InvalidValueException;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.metrics.DHTMetrics;
import io.bosonnetwork.kademlia.protocol.AnnouncePeerRequest;
import io.bosonnetwork.kademlia.protocol.Error;
import io.bosonnetwork.kademlia.protocol.FindNodeRequest;
import io.bosonnetwork.kademlia.protocol.FindNodeResponse;
import io.bosonnetwork.kademlia.protocol.FindPeerRequest;
import io.bosonnetwork.kademlia.protocol.FindValueRequest;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.protocol.StoreValueRequest;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.routing.RoutingTable;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.kademlia.rpc.RpcCallListener;
import io.bosonnetwork.kademlia.rpc.RpcServer;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.security.SuspiciousNodeDetector;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.kademlia.tasks.ClosestSet;
import io.bosonnetwork.kademlia.tasks.NodeLookupTask;
import io.bosonnetwork.kademlia.tasks.PeerAnnounceTask;
import io.bosonnetwork.kademlia.tasks.PeerLookupTask;
import io.bosonnetwork.kademlia.tasks.PingRefreshTask;
import io.bosonnetwork.kademlia.tasks.Task;
import io.bosonnetwork.kademlia.tasks.TaskManager;
import io.bosonnetwork.kademlia.tasks.ValueAnnounceTask;
import io.bosonnetwork.kademlia.tasks.ValueLookupTask;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Variable;
import io.bosonnetwork.vertx.BosonVerticle;

public class DHT extends BosonVerticle {
	private final Identity identity;

	private final Network network;
	private final String host;
	private final int port;

	private final NodeInfo nodeInfo;

	// Kademlia parameters, received as plain values from KadNode: nothing below KadNode knows about
	// NodeConfiguration.KademliaOptions.
	private final int k;
	private final int alpha;
	private final int replacements;
	private final int concurrentTasks;

	// Routing-table sizes at which bootstrapping kicks in, derived from k so that "enough contacts to
	// operate" keeps meaning the same thing at any bucket size. See KadConstants for the rationale.
	private final int bootstrapThreshold;
	private final int useBootstrapNodesThreshold;

	private final DataStorage storage;
	private final Blacklist blacklist;
	private final TokenManager tokenManager;

	private final boolean enableSpamThrottling;
	private final boolean enableSuspiciousNodeTracking;
	private final boolean enableDeveloperMode;
	private final DHTMetrics metrics;

	private final KadContext kadContext;
	private RpcServer rpcServer;

	// Read from the sibling's context in populateClosestNodes, written from the KadNode context
	// during deployment; volatile so the wiring and unwiring are visible to both event loops.
	private volatile @Nullable DHT sibling;

	private volatile boolean running;
	private ConnectionStatus status;
	private DHTConnectionStatusListener connectionStatusListener;

	private List<NodeInfo> bootstrapNodes;
	private List<Id> bootstrapIds;
	private boolean bootstrapping;
	private long lastBootstrap;

	// Whether the "nothing to bootstrap from" warning has already been logged. That condition is a
	// static misconfiguration rather than an event - no bootstrap nodes configured and an empty
	// routing table - so it would otherwise be reported on every update tick for as long as it lasts.
	private boolean warnedNoBootstrapSource;

	// True until the first bootstrap that actually runs has finished.
	//
	// Only that first bootstrap enqueues its lookups at the head of the task queue. Until the routing
	// table exists the node cannot answer anything, so racing to fill it is worth preempting whatever
	// else is queued. Every later bootstrap is routine maintenance - the periodic self-lookup, or a
	// table that thinned out - and must not push application lookups behind it. The flag is only
	// cleared once a bootstrap reaches completion, so an attempt that returns early (rate-limited, or
	// nothing to contact) leaves the priority for the one that really does the work.
	private boolean initialBootstrap = true;

	private final RoutingTable routingTable;
	private long lastMaintenance;
	private final Path persistFile;

	// True when this run began by loading a persisted routing table. Set in start(), not here, so a
	// redeployed instance re-decides: state whose lifetime is a deployment must not be initialized with
	// the lifetime of the object.
	private boolean loadedRoutingTable;

	private final List<Long> timers;

	private final SuspiciousNodeDetector suspiciousNodeDetector;

	private TaskManager taskManager;

	private final Map<KBucket, Task<?>> maintenanceTasks = new IdentityHashMap<>();

	private static final Logger log = LoggerFactory.getLogger(DHT.class);

	// Package-private: DHTSiblingTests drives populateClosestNodes directly.
	record ClosestNodes(List<? extends NodeInfo> nodes4, List<? extends NodeInfo> nodes6) {}

	/**
	 * Creates a DHT for one address family.
	 * <p>
	 * The Kademlia parameters arrive as plain values rather than as a configuration object: only
	 * {@code KadNode} reads {@code NodeConfiguration.KademliaOptions}, and it hands the individual
	 * values down from there.
	 * </p>
	 *
	 * <p>
	 * This constructor is the single point where all four Kademlia parameters arrive, so it is where
	 * they are validated. A non-positive value would not fail loudly downstream: {@code alpha < 1}
	 * makes {@code Task.canDoRequest()} permanently false, so a task never issues an RPC and - since
	 * iteration is driven only by call state changes - never completes; {@code concurrentTasks < 1}
	 * makes {@code TaskManager.isReady()} permanently false, so every task queues forever. Both are
	 * silent hangs, which is why they are rejected here rather than left to the caller.
	 * </p>
	 *
	 * @param alpha             the lookup concurrency parameter, at least 1.
	 * @param k                 the Kademlia bucket size, at least 1.
	 * @param replacements      the per-bucket replacement cache size, at least 1.
	 * @param concurrentTasks   the ceiling on concurrently running tasks, at least 1; further tasks are queued.
	 * @throws IllegalArgumentException if any Kademlia parameter is less than 1.
	 */
	public DHT(Identity identity, Network network, String host, int port, Collection<NodeInfo> bootstrapNodes,
	           int alpha, int k, int replacements, int concurrentTasks,
	           DataStorage storage, Path persistFile, TokenManager tokenManager,
	           Blacklist blacklist, boolean enableSpamThrottling, boolean enableSuspiciousNodeTracking,
	           boolean enableDeveloperMode, DHTMetrics metrics) {
		if (alpha < 1)
			throw new IllegalArgumentException("Invalid alpha: " + alpha);
		if (k < 1)
			throw new IllegalArgumentException("Invalid k: " + k);
		if (replacements < 1)
			throw new IllegalArgumentException("Invalid replacements: " + replacements);
		if (concurrentTasks < 1)
			throw new IllegalArgumentException("Invalid concurrentTasks: " + concurrentTasks);

		this.identity = identity;
		this.network = network;
		this.host = host;
		this.port = port;
		this.storage = storage;
		this.persistFile = persistFile;
		this.tokenManager = tokenManager;
		this.blacklist = blacklist;

		this.enableSpamThrottling = enableSpamThrottling;
		this.enableSuspiciousNodeTracking = enableSuspiciousNodeTracking;
		this.enableDeveloperMode = enableDeveloperMode;
		this.metrics = metrics;

		this.alpha = alpha;
		this.k = k;
		this.replacements = replacements;
		this.concurrentTasks = concurrentTasks;

		// Both tiers scale with k up to an absolute ceiling. Capping only one would break the invariant
		// that the node-fallback tier sits strictly below the bootstrap tier: at large k they would
		// collide and then invert, collapsing the self-bootstrap band and sending routine maintenance
		// to the shared bootstrap nodes. See KadConstants for the arithmetic.
		this.bootstrapThreshold = Math.min(KadConstants.BOOTSTRAP_THRESHOLD_BUCKETS * k,
				KadConstants.BOOTSTRAP_THRESHOLD_ENTRIES);
		this.useBootstrapNodesThreshold = Math.min(KadConstants.USE_BOOTSTRAP_NODES_THRESHOLD_BUCKETS * k,
				KadConstants.USE_BOOTSTRAP_NODES_THRESHOLD_ENTRIES);

		this.routingTable = new RoutingTable(identity.getId(), k, replacements);

		this.status = ConnectionStatus.Disconnected;
		this.running = false;

		this.bootstrapping = false;
		this.lastBootstrap = 0;

		this.timers = new ArrayList<>(6);

		this.bootstrapNodes = List.of();
		this.bootstrapIds = List.of();

		// Initialize suspicious node tracker
		this.suspiciousNodeDetector = enableSuspiciousNodeTracking ?
				SuspiciousNodeDetector.create() : SuspiciousNodeDetector.disabled();

		if (bootstrapNodes != null && !bootstrapNodes.isEmpty())
			addBootstrapNodes(bootstrapNodes);

		this.kadContext = new KadContext(this);

		// TODO: improve
		this.nodeInfo = NodeInfo.of(identity.getId(), host, port);
	}

	public final int getAlpha() {
		return alpha;
	}

	public final int getK() {
		return k;
	}

	public final int getReplacements() {
		return replacements;
	}

	public final int getConcurrentTasks() {
		return concurrentTasks;
	}

	public final boolean isDeveloperMode() {
		return enableDeveloperMode;
	}

	public boolean isRunning() {
		return running;
	}

	public Network getNetwork() {
		return network;
	}

	Identity getIdentity() {
		return identity;
	}

	public List<NodeInfo> getBootstrapNodes() {
		return bootstrapNodes;
	}

	public NodeInfo getNodeInfo() {
		return nodeInfo;
	}

	public RpcServer getRpcServer() {
		return rpcServer;
	}

	/**
	 * Whether the local socket currently appears able to carry traffic.
	 * <p>
	 * False means we have sent requests and heard nothing back for a while, so anything we send now is
	 * most likely going nowhere. It is a signal about <em>our</em> connectivity, not about any peer, and
	 * it is the gate for self-initiated background work: there is no point spending an iterative lookup
	 * on a network that cannot answer. Work the application asked for is never gated on it - the
	 * application's request outranks our guess, and a call that succeeds is itself proof we were wrong.
	 * </p>
	 *
	 * @return {@code true} if the DHT is running and its RPC server considers itself reachable
	 */
	public boolean isReachable() {
		return rpcServer != null && rpcServer.isReachable();
	}

	public RoutingTable getRoutingTable() {
		return routingTable;
	}

	public void setSibling(@Nullable DHT dht) {
		if (dht == this)
			throw new IllegalArgumentException("Can not set self as sibling");

		this.sibling = dht;
	}

	public @Nullable DHT getSibling() {
		return sibling;
	}

	public void setConnectionStatusListener(DHTConnectionStatusListener listener) {
		this.connectionStatusListener = listener;
	}

	@Override
	protected Future<Void> deploy() {
		if (running)
			return Future.succeededFuture();

		log.info("Starting DHT {}:{} on {}:{}......", network, identity.getId(), host, port);

		// Bootstrap state belongs to a deployment, not to this object, so it is reset here as well as in
		// the constructor. Carrying it across a redeploy is silently wrong in every direction: a stale
		// lastBootstrap makes the startup bootstrap trip its own rate limiter and return immediately, so
		// the node skips the one bootstrap whose latency matters and waits out the rest of an interval it
		// never used; a bootstrapping flag left set by a shutdown mid-attempt blocks every attempt for
		// good; and maintenanceTasks entries whose tasks died with the previous deployment exclude those
		// buckets from both refresh paths permanently.
		bootstrapping = false;
		lastBootstrap = 0;
		initialBootstrap = true;
		warnedNoBootstrapSource = false;
		maintenanceTasks.clear();
		lastMaintenance = 0;

		if (persistFile != null && Files.exists(persistFile) && Files.isRegularFile(persistFile)) {
			log.info("Loading routing table from {} ...", persistFile);
			routingTable.load(persistFile);
			this.loadedRoutingTable = !routingTable.isEmpty();
		} else {
			this.loadedRoutingTable = false;
		}

		rpcServer = new RpcServer(kadContext, host, port, blacklist, suspiciousNodeDetector, enableSpamThrottling, metrics);
		rpcServer.setMessageHandler(this::onMessage);
		rpcServer.setCallSentHandler(this::onSend);
		rpcServer.setCallTimeoutHandler(this::onTimeout);
		return rpcServer.start().map(v -> {
			this.taskManager = new TaskManager(kadContext);
			setStatus(ConnectionStatus.Connecting);

			rpcServer.setReachableHandler(reachable -> {
				if (reachable) {
					setStatus(ConnectionStatus.Connected);
				} else {
					randomPing(0);
					setStatus(ConnectionStatus.Disconnected);
				}
			});

			List<Future<Void>> connectFutures = new ArrayList<>(2);

			// One future for the whole sweep, not one per bucket: this gates the connection status, and
			// the status question is answered by the first cached contact that answers rather than by
			// the last one to time out. See pingRoutingTable.
			if (loadedRoutingTable)
				connectFutures.add(pingRoutingTable());

			Future<Void> bootstrapFuture = doBootstrap(bootstrapNodes);
			connectFutures.add(bootstrapFuture);

			Future.all(connectFutures).onComplete(ar -> {
				log.info("DHT {}:{} startup bootstrap finished.", network, identity.getId());
				if (routingTable.getNumberOfEntries() > 0)
					setStatus(ConnectionStatus.Connected);
				else
					setStatus(ConnectionStatus.Disconnected);
			});

			setupPeriodicTasks();
			return (Void) null;
		}).andThen(ar -> {
			if (ar.succeeded()) {
				running = true;
				log.info("Started DHT {}:{} on {}:{}.", network, identity.getId(), host, port);
			} else {
				running = false;
				log.error("Failed to start DHT {}:{} on {}:{}.", network, identity.getId(), host, port, ar.cause());
			}
		});
	}

	@Override
	protected Future<Void> undeploy() {
		if (!running)
			return Future.succeededFuture();

		running = false;
		log.info("Stopping DHT {}:{} on {}:{}......", network, identity.getId(), host, port);

		return Future.succeededFuture().map(v -> {
			setStatus(ConnectionStatus.Disconnected);

			cancelPeriodicTasks();

			if (taskManager != null) {
				taskManager.cancelAll();
				taskManager = null;
			}

			return null;
		}).compose(v -> {
			if (rpcServer == null) {
				return Future.succeededFuture();
			} else {
				rpcServer.setReachableHandler(null);
				return rpcServer.stop().andThen(ar -> rpcServer = null);
			}
		}).andThen(ar -> {
			if (persistFile != null) {
				try {
					log.info("Persisting routing table on shutdown...");
					routingTable.save(persistFile);
				} catch (IOException e) {
					log.error("Persisting routing table failed", e);
				}
			}

			if (ar.succeeded())
				log.info("Stopped DHT {}:{} on {}:{}.", network, identity.getId(), host, port);
			else
				log.error("Failed to stop DHT {}:{} on {}:{}.", network, identity.getId(), host, port, ar.cause());
		});
	}

	private void setupPeriodicTasks() {
		long timer = kadContext.setPeriodic(KadConstants.DHT_UPDATE_INTERVAL, KadConstants.DHT_UPDATE_INTERVAL, this::update);
		timers.add(timer);

		// deep lookup to make ourselves known to random parts of the keyspace
		timer = kadContext.setPeriodic(KadConstants.RANDOM_LOOKUP_INTERVAL, KadConstants.RANDOM_LOOKUP_INTERVAL, this::randomLookup);
		timers.add(timer);

		// Do random node ping to check socket liveness
		timer = kadContext.setPeriodic(KadConstants.RANDOM_PING_INTERVAL, KadConstants.RANDOM_PING_INTERVAL, this::randomPing);
		timers.add(timer);

		if (enableSuspiciousNodeTracking) {
			timer = kadContext.setPeriodic(KadConstants.SUSPICIOUS_NODES_PURGE_INITIAL_DELAY, KadConstants.SUSPICIOUS_NODES_PURGE_INTERVAL, unused -> suspiciousNodeDetector.purge());
			timers.add(timer);
		}

		if (persistFile != null) {
			timer = kadContext.setPeriodic(KadConstants.ROUTING_TABLE_PERSIST_INITIAL_DELAY, KadConstants.ROUTING_TABLE_PERSIST_INTERVAL, this::persistRoutingTable);
			timers.add(timer);
		}
	}

	private void cancelPeriodicTasks() {
		timers.forEach(kadContext::cancelTimer);
		timers.clear();
	}

	private void update(long unusedTimerId) {
		if (!running)
			return;

		log.info("Periodic: DHT update...");

		routingTableMaintenance();

		switch (selectBootstrapTier(routingTable.getNumberOfEntries(), System.currentTimeMillis(),
				rpcServer.isReachable())) {
			// Capped here and nowhere else: this is the attempt that repeats, every BOOTSTRAP_INTERVAL
			// for as long as the node stays thin or deaf. See selectBootstrapNodes.
			case BOOTSTRAP_NODES -> doBootstrap(selectBootstrapNodes(bootstrapNodes));
			case SELF -> doBootstrap(Collections.emptyList());
			case NONE -> { }
		}
	}

	/**
	 * Which bootstrap the periodic update should run, if any.
	 */
	enum BootstrapTier {
		/** Nothing due: the table is healthy and the periodic self-lookup has not come round. */
		NONE,
		/** Self-bootstrap: a lookup seeded only from contacts we already hold. */
		SELF,
		/** Seed the lookup with the configured bootstrap nodes - shared infrastructure. */
		BOOTSTRAP_NODES
	}

	/**
	 * Decides whether the periodic update should bootstrap, and from what.
	 * <p>
	 * Two independent questions, in order. First, is a bootstrap due at all: the routing table is below
	 * the point where it can be relied on to route, or the periodic self-lookup that keeps us present in
	 * other nodes' tables has come round. Second, which tier - below one bucket's worth of contacts we
	 * may be unable to reach the network unaided and fall back to the configured bootstrap nodes;
	 * above it we self-bootstrap from what we already know and leave that shared resource alone.
	 * </p>
	 * <p>
	 * <b>Why being unreachable overrides both questions.</b> Every rule above reasons from the routing
	 * table, and a deaf node's table tells it nothing: the contacts are all still there, none of them
	 * answers, and the node cannot tell the difference. Worse, that table cannot repair itself, because
	 * every path that evicts a dead entry is either gated on reachability - {@code onTimeout} and the
	 * ping-refresh maintenance both are, deliberately, so that our own outage does not punish peers - or
	 * does not evict for staleness at all, which is the case for {@code KBucket.cleanup}. So the entry
	 * count stays wherever the outage left it, forever.
	 * </p>
	 * <p>
	 * A node deaf with a full table would therefore never fall below any threshold, never select the
	 * bootstrap-nodes tier, and never contact a bootstrap node again - while {@code randomPing}, the
	 * probe that is meant to notice the network returning, pinged contacts that no longer answer. That
	 * is the ordinary case of a laptop suspending on one network and waking on another, and it stranded
	 * the node permanently. Deafness is itself the evidence that the cached contacts are not working,
	 * which is precisely when the configured bootstrap nodes are the right thing to ask, so it makes a
	 * bootstrap due now rather than on the self-lookup clock. The cost is bounded by
	 * {@link KadConstants#BOOTSTRAP_INTERVAL} and comes to one packet per configured bootstrap node.
	 * </p>
	 * <p>
	 * Side-effect free and a pure function of its arguments, so the decision can be tested without a
	 * socket or a deployed verticle.
	 * </p>
	 *
	 * @param entries the current number of routing table entries
	 * @param now the current time, in milliseconds
	 * @param reachable whether the RPC server currently considers itself reachable
	 * @return the tier to bootstrap from, or {@link BootstrapTier#NONE}
	 */
	BootstrapTier selectBootstrapTier(int entries, long now, boolean reachable) {
		if (!reachable)
			return BootstrapTier.BOOTSTRAP_NODES;

		if (entries >= bootstrapThreshold && now - lastBootstrap <= KadConstants.SELF_LOOKUP_INTERVAL)
			return BootstrapTier.NONE;

		return entries < useBootstrapNodesThreshold ? BootstrapTier.BOOTSTRAP_NODES : BootstrapTier.SELF;
	}

	private void routingTableMaintenance() {
		long now = System.currentTimeMillis();
		if (now - lastMaintenance < KadConstants.ROUTING_TABLE_MAINTENANCE_INTERVAL)
			return;

		log.info("Routing table maintenance ...");
		lastMaintenance = now;

		// Deliberately not gated on reachability, unlike the other periodic work. A maintenance pass is
		// local bookkeeping - merging buckets, cleaning up entries, promoting verified replacements -
		// and all of it stays correct while the socket is deaf. The only part that touches the network
		// is the refresh, and tryPingMaintenance already returns early when unreachable.
		//
		// The handler collects rather than dispatches: RoutingTable reports which buckets want a
		// refresh, and how many of them we can afford this pass is a policy question that belongs here,
		// next to the other two selections. See selectBucketsToRefresh.
		List<KBucket> candidates = new ArrayList<>();
		routingTable.maintenance(bootstrapIds, candidates::add);

		// removeOnTimeout stays false here, unlike the warm-start sweep. This is steady state: a single
		// timeout is weak evidence, and entries that keep failing are demoted by the routing table
		// anyway. Purging a stale cache is the sweep's job and the sweep now finishes it, so there is
		// nothing left here for a one-shot to clean up.
		for (KBucket bucket : selectBucketsToRefresh(candidates))
			tryPingMaintenance(bucket, false, false, true,
					"RoutingTable maintenance: refreshing bucket - " + bucket.prefix());
	}

	/**
	 * Chooses which of the buckets asking for a refresh this pass can afford.
	 * <p>
	 * Package-private and free of side effects apart from the sort, so the policy can be tested without
	 * a live network - the same shape as {@link #selectBucketsToFill} and {@link #selectBucketsToPing}.
	 * </p>
	 * <p>
	 * <b>Why this needs a budget at all.</b> {@code RoutingTable.maintenance} reports every bucket that
	 * wants a refresh, and this used to turn all of them into tasks. That is the same
	 * unbounded-per-bucket shape {@link #selectBucketsToFill} and {@link #selectBucketsToPing} fixed on
	 * the other two paths, and it is the one that repeats for the life of the node - so a warm start
	 * did not avoid the burst, it postponed it to the first maintenance pass.
	 * </p>
	 * <p>
	 * <b>The budget is against live tasks, not against this pass.</b> {@code maintenanceTasks} holds
	 * exactly the refreshes still running, so subtracting it bounds the work in flight no matter how
	 * passes overlap. A per-pass budget would only bound the burst if every task always finished inside
	 * {@link KadConstants#ROUTING_TABLE_MAINTENANCE_INTERVAL}, which is true of today's constants and
	 * is not a property worth depending on.
	 * </p>
	 * <p>
	 * <b>Nothing is starved, and that is what the first sort key buys.</b> Ordering by distance alone
	 * would starve the tail outright: a pass serves the nearest few, they become eligible again one
	 * {@link KadConstants#BUCKET_REFRESH_INTERVAL} later, and being nearest they are chosen again ahead
	 * of buckets that have never been served at all. With more eligible buckets than a refresh interval
	 * has capacity for - which is exactly a warm start, where the whole loaded table is stale at once -
	 * everything past that capacity would be refreshed never. Least-recently-refreshed first fixes it
	 * for good, because {@code PingRefreshTask} stamps what it covers: a served bucket sorts behind
	 * every unserved one, so the whole table is reached before anything repeats. The same key, for the
	 * same reason, orders {@link #selectBucketsToFill}.
	 * </p>
	 * <p>
	 * Distance breaks the tie, which is what makes the warm start behave as intended without a special
	 * case: {@code load()} does not stamp the buckets it reads, so they all arrive tied at zero and the
	 * nearest is served first.
	 * </p>
	 *
	 * @param candidates the buckets {@code RoutingTable.maintenance} reported, in table order.
	 * @return the buckets to refresh now, longest-unrefreshed first and nearest first among equals,
	 * 		   within what the budget still allows.
	 */
	List<KBucket> selectBucketsToRefresh(List<KBucket> candidates) {
		// A quarter of the slots, matching MAX_BUCKET_FILLS_PER_BOOTSTRAP at the default and holding the
		// same relation at any configured value. Half, as the warm-start sweep takes, would be too much
		// for work that runs forever rather than once.
		int budget = Math.max(1, concurrentTasks / 4) - maintenanceTasks.size();
		if (budget <= 0)
			return List.of();

		if (candidates.size() <= budget)
			return candidates;

		Id localId = identity.getId();
		candidates.sort(Comparator.comparingLong(KBucket::lastRefresh)
				.thenComparing((a, b) -> localId.threeWayCompare(a.prefix(), b.prefix())));
		return candidates.subList(0, budget);
	}

	private void randomLookup(long unusedTimerId) {
		if (rpcServer.isReachable()) {
			log.info("Periodic: random lookup ...");
			NodeLookupTask task = new NodeLookupTask(kadContext, Id.random())
					.setName("Periodic: random node Lookup");
			taskManager.add(task);
		} else {
			log.info("Periodic: not performing random lookup, node is unreachable.");
		}
	}

	private void randomPing(long unusedTimerId) {
		if (!rpcServer.hasPendingCalls()) {
			log.info("Periodic: random ping...");
			KBucketEntry entry = routingTable.getRandomEntry();
			if (entry != null) {
				Message request = Message.pingRequest();
				RpcCall c = new RpcCall(entry, request);
				rpcServer.sendCall(c);
			}
		} else {
			log.info("Periodic: random ping - skip due to node has pending calls.");
		}
	}

	private void persistRoutingTable(long unusedTimerId) {
		try {
			log.info("Periodic: persisting routing table ...");
			routingTable.save(persistFile);
		} catch (IOException e) {
			log.error("Can not save the routing table: {}", e.getMessage(), e);
		}
	}

	public Future<Void> bootstrap(Collection<NodeInfo> nodes) {
		if (!running)
			return Future.failedFuture(new IllegalStateException("DHT is not running"));

		Promise<Void> promise = Promise.promise();

		runOnContext(v -> {
			Collection<NodeInfo> added = addBootstrapNodes(nodes);
			if (bootstrapping) {
				promise.fail(new IllegalStateException("DHT is bootstrapping"));
				return;
			}

			lastBootstrap = 0; // force to bootstrap
			if (status == ConnectionStatus.Disconnected)
				setStatus(ConnectionStatus.Connecting);

			doBootstrap(added).onComplete(promise);
		});

		return promise.future();
	}

	public Future<Void> bootstrap() {
		if (!running)
			return Future.failedFuture(new IllegalStateException("DHT is not running"));

		Promise<Void> promise = Promise.promise();

		runOnContext(v -> {
			if (bootstrapping) {
				promise.fail(new IllegalStateException("DHT is bootstrapping"));
				return;
			}

			lastBootstrap = 0; // force to bootstrap
			if (status == ConnectionStatus.Disconnected)
				setStatus(ConnectionStatus.Connecting);

			doBootstrap(Collections.emptyList()).onComplete(promise);
		});

		return promise.future();
	}

	/**
	 * The quiet period before another bootstrap may run: {@link KadConstants#BOOTSTRAP_INTERVAL}
	 * with a fresh symmetric jitter drawn for this attempt.
	 * <p>
	 * The interval on its own is a fixed period, so nodes that started together stay in lockstep
	 * indefinitely - a fleet rolled out at once, or a whole population reconnecting after the same
	 * outage, hits the shared bootstrap nodes in synchronised waves. Redrawing an offset per attempt
	 * lets each node's phase random-walk away from the others'. Centred on the interval rather than
	 * added to it, so randomising the individual wait does not also slow the long-run cadence.
	 * </p>
	 * <p>
	 * See {@link KadConstants#BOOTSTRAP_INTERVAL_JITTER_PERCENT} for why the band has to be as wide as
	 * it is, and for why this stays a flat interval rather than backing off exponentially.
	 * </p>
	 *
	 * @return the interval to enforce for this attempt, in milliseconds
	 */
	static long bootstrapInterval() {
		int jitter = KadConstants.BOOTSTRAP_INTERVAL / 100 * KadConstants.BOOTSTRAP_INTERVAL_JITTER_PERCENT;
		return KadConstants.BOOTSTRAP_INTERVAL + ThreadLocalRandom.current().nextInt(-jitter, jitter + 1);
	}

	/**
	 * Picks the bootstrap nodes to contact on a periodic attempt.
	 * <p>
	 * A pure function of the configured list, so it stays testable without a socket - the same shape as
	 * {@link #selectBootstrapTier} and {@code selectBucketsToFill}.
	 * </p>
	 * <p>
	 * <b>Why only some of them.</b> Contacting every configured bootstrap node on every attempt makes a node's
	 * load on shared infrastructure scale with how long the operator's list is, which is backwards -
	 * listing more bootstrap nodes for redundancy should not cost more traffic. It matters at population scale
	 * rather than per node: the tier that reaches this code only fires for a node that is deaf or whose
	 * table is thin, and after a network-wide outage that is everybody at once, all retrying against
	 * the same handful of hosts.
	 * </p>
	 * <p>
	 * <b>What it does not cost.</b> A thin-table node still gets up to
	 * {@code BOOTSTRAP_NODES_PER_ATTEMPT * MAX_NODES_PER_RESPONSE} seeds from one attempt, more than
	 * a lookup's candidate queue holds. A deaf node still recovers: while the socket is deaf the choice
	 * is irrelevant because nothing arrives either way, and once it works again a single answer
	 * restores reachability. Drawing fresh each attempt rather than keeping a chosen set is what keeps
	 * a node from being permanently unlucky.
	 * </p>
	 * <p>
	 * Applied to the periodic path only. The startup bootstrap contacts every node, because first
	 * contact is where latency matters most, and {@link #bootstrap(Collection)} contacts every node
	 * it was handed, because the application named those explicitly.
	 * </p>
	 *
	 * @param bootstrapNodes the configured bootstrap nodes.
	 * @return the nodes to contact on this attempt.
	 */
	static List<NodeInfo> selectBootstrapNodes(Collection<NodeInfo> bootstrapNodes) {
		if (bootstrapNodes.size() <= KadConstants.BOOTSTRAP_NODES_PER_ATTEMPT)
			return List.copyOf(bootstrapNodes);

		List<NodeInfo> shuffled = new ArrayList<>(bootstrapNodes);
		Collections.shuffle(shuffled, ThreadLocalRandom.current());
		return List.copyOf(shuffled.subList(0, KadConstants.BOOTSTRAP_NODES_PER_ATTEMPT));
	}

	/**
	 * Asks the given bootstrap nodes for a random target, and collects the nodes they return.
	 * <p>
	 * <b>Resolves on the first useful answer, not the last.</b> Waiting for every call to settle means
	 * waiting at the pace of the worst node, and a dead one only settles at the RPC timeout - ten
	 * seconds during which the startup bootstrap holds {@link ConnectionStatus#Connected} back even
	 * though another node answered in milliseconds and the table was usable immediately. So the first
	 * response arms a short grace timer ({@link KadConstants#BOOTSTRAP_NODE_GRACE}), and whatever has
	 * arrived when it expires is the result.
	 * </p>
	 * <p>
	 * Any response arms it, including one from a node whose own table is empty. That node gave us
	 * nothing to seed with, but it did prove the path works, and the grace is wide enough to still catch
	 * a slower node that does have nodes. Waiting on the empty answer instead would reintroduce the
	 * very delay this avoids whenever a fresh node shares a list with a dead one.
	 * </p>
	 * <p>
	 * <b>Nothing is abandoned by resolving early.</b> The outstanding calls are still outstanding, and
	 * a late response still runs through the normal receive path, so its sender still enters the
	 * routing table. All that is given up is the node list it carried, by which point we already have
	 * another node's list and a lookup running on it.
	 * </p>
	 * <p>
	 * Confined to this DHT's event loop: call listeners and the timer both fire on this context, so the
	 * accumulator needs no synchronization. The result is copied out of it because a straggler may
	 * still write to it afterwards.
	 * </p>
	 *
	 * @param bootstrapNodes the nodes to contact.
	 * @return the deduplicated nodes they returned; empty if none answered.
	 */
	private Future<Collection<NodeInfo>> askBootstrapNodes(Collection<NodeInfo> bootstrapNodes) {
		// Nothing to wait for. Guarded here rather than only at the call site: the promise below is
		// completed by call listeners, so with no calls to make there would be nobody to complete it.
		if (bootstrapNodes.isEmpty())
			return Future.succeededFuture(Collections.emptyList());

		Promise<Collection<NodeInfo>> promise = Promise.promise();
		Map<Id, NodeInfo> nodes = new HashMap<>();
		// Single-threaded by context confinement; the holders exist so the listeners can capture and
		// mutate them, not for thread safety.
		Variable<Integer> outstanding = Variable.of(bootstrapNodes.size());
		Variable<Long> graceTimer = Variable.empty();

		Runnable resolve = () -> {
			if (promise.tryComplete(List.copyOf(nodes.values())) && graceTimer.isPresent()) {
				kadContext.cancelTimer(graceTimer.get());
				graceTimer.clear();
			}
		};

		for (NodeInfo bootstrapNode : bootstrapNodes) {
			Message request = Message.findNodeRequest(Id.random(), network.isIPv4(), network.isIPv6());
			RpcCall call = new RpcCall(bootstrapNode, request).addListener(new RpcCallListener() {
				@Override
				public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
					if (!state.isFinal())
						return;

					if (state == RpcCall.State.RESPONDED) {
						for (NodeInfo node : c.getResponse().<FindNodeResponse>getBody().getNodes(network))
							nodes.put(node.getId(), node);

						if (graceTimer.isEmpty() && !promise.future().isComplete())
							graceTimer.set(kadContext.setTimer(KadConstants.BOOTSTRAP_NODE_GRACE,
									unused -> resolve.run()));
					}

					if (outstanding.updateAndGet(v -> v - 1) == 0)
						resolve.run();
				}
			});

			rpcServer.sendCall(call);
		}

		return promise.future();
	}

	private Future<Void> doBootstrap(Collection<NodeInfo> bootstrapNodes) {
		if (bootstrapping)
			return Future.failedFuture(new IllegalStateException("DHT is bootstrapping"));

		if (System.currentTimeMillis() - lastBootstrap < bootstrapInterval())
			return Future.succeededFuture();

		if (bootstrapNodes.isEmpty() && routingTable.getNumberOfEntries() == 0) {
			// Deliberately does not stamp lastBootstrap. There is no address to send anything to, so
			// nothing was attempted and there is nothing to rate-limit - and the state cannot change by
			// itself, only when an inbound packet hands us a contact. Stamping here would make the node
			// sit out a full interval it never used before it could act on that contact. The warning is
			// logged once rather than on every tick, since it reports a static misconfiguration.
			if (!warnedNoBootstrapSource) {
				log.warn("No bootstrap nodes found, and the routingtable is empty, skipping bootstrap.");
				warnedNoBootstrapSource = true;
			}
			return Future.succeededFuture();
		}

		warnedNoBootstrapSource = false;

		bootstrapping = true;
		log.info("DHT {}:{} bootstrapping...", network, identity.getId());

		return askBootstrapNodes(bootstrapNodes).compose(nodes -> {
			// breadth-first lookup: fill more buckets.
			//
			// Skipped when the attempt has nothing to work with. An empty table is the obvious case. The
			// other is being deaf with no answer from any bootstrap node: the lookup would then run entirely
			// against contacts that are not answering, and a deaf node retries every BOOTSTRAP_INTERVAL,
			// so that would be a full iterative lookup's worth of timeouts on repeat. Keeping it out
			// holds the cost of a deaf retry at what it should be - one packet per configured node. A
			// bootstrap node that did answer puts nodes in hand, and then the lookup is exactly what we want.
			boolean nothingToLookupWith = nodes.isEmpty() &&
					(routingTable.getNumberOfEntries() == 0 || !rpcServer.isReachable());
			return nothingToLookupWith ? Future.succeededFuture() : fillHomeBucket(nodes);
		}).compose(v -> {
			// depth-first lookup: fill each bucket
			// only if the routing table is more than 1 bucket, and only while the socket can carry the
			// traffic - this is the expensive half of a bootstrap, and while we are deaf every one of
			// those lookups can do nothing but time out. The gate lives here rather than in
			// selectBucketsToFill so that method stays a pure function of the routing table.
			return (routingTable.size() <= 1 || !rpcServer.isReachable()) ?
					Future.succeededFuture() : fillBuckets();
		}).andThen(ar -> {
			// Only the first bootstrap to get this far may preempt the queue; see initialBootstrap.
			initialBootstrap = false;
			bootstrapping = false;
			lastBootstrap = System.currentTimeMillis();
			log.info("DHT {}:{} bootstrapping finished", network, identity.getId());
		});
	}

	private Collection<NodeInfo> addBootstrapNodes(Collection<NodeInfo> nodes) {
		if (nodes.isEmpty())
			return List.of();

		Map<Id, NodeInfo> dedup = new HashMap<>(this.bootstrapNodes.size() + nodes.size());
		bootstrapNodes.forEach(node -> dedup.put(node.getId(), node));

		Map<Id, NodeInfo> added = new HashMap<>(nodes.size());
		for (NodeInfo node : nodes) {
			if (!node.hasAddress(network.protocolFamily()))
				continue;

			if (node.getId().equals(identity.getId())) {
				log.warn("Can not bootstrap from local node");
				continue;
			}

			// Ensure bootstrap nodes only use a single address compatible with this DHT's network family.
			NodeInfo bootstrapNode = node.narrowDown(network.protocolFamily());
			dedup.put(bootstrapNode.getId(), bootstrapNode);
			added.put(bootstrapNode.getId(), bootstrapNode);
		}

		bootstrapNodes = List.copyOf(dedup.values());
		bootstrapIds = List.copyOf(dedup.keySet());
		return added.values();
	}

	private Future<Void> fillHomeBucket(Collection<NodeInfo> nodes) {
		Promise<Void> promise = Promise.promise();
		NodeLookupTask task = new NodeLookupTask(kadContext, identity.getId())
				.setName("Bootstrap: filling home bucket")
				.setBootstrap(true)
				.injectCandidates(nodes)
				.addListener(t -> promise.complete());
		taskManager.add(task, initialBootstrap);

		return promise.future();
	}

	/**
	 * The depth-first phase of bootstrap: top up under-populated buckets by looking up a random id
	 * inside each one's prefix.
	 * <p>
	 * This is the routing-table refresh of the Kademlia paper (section 2.3), and the lookup per bucket
	 * is the protocol-prescribed part. What is <em>not</em> prescribed is how many buckets to do at
	 * once, and that is what the budget here controls.
	 * </p>
	 * <p>
	 * <b>Why the fan-out has to be bounded.</b> Unbounded, this loop sizes itself from the routing
	 * table - one full iterative lookup per eligible bucket - while the queue it shares is fixed at
	 * {@code concurrentTasks}. So the wider the table, the more this crowds out everything else, which
	 * is backwards: the nodes with the most contacts are the ones carrying the most application
	 * traffic. Frequency compounds it. Bootstrap is not only a startup step: {@link #update(long)} calls
	 * {@link #doBootstrap} whenever the table is below {@link #bootstrapThreshold} <em>or</em> the
	 * {@link KadConstants#SELF_LOOKUP_INTERVAL} self-lookup is due, and the only brake is
	 * {@link KadConstants#BOOTSTRAP_INTERVAL}. A node whose table sits below the threshold - a young
	 * node, a small network, a node behind a bad NAT - therefore re-runs this every four minutes rather
	 * than every thirty. And below that threshold the "skip full buckets" rule never fires either, so
	 * every non-empty bucket qualifies every time. The burst was largest exactly where the table was
	 * weakest, which is when the node can least afford to spend its budget on maintenance.
	 * </p>
	 * <p>
	 * <b>The cost being bounded.</b> Each of these is a full iterative lookup, not a ping: convergence
	 * takes {@code k} responses to fill the closest set plus
	 * {@link KadConstants#LOOKUP_STABILITY_ATTEMPTS} + 1 more to call it stable, so roughly 25 at the
	 * default k. Twenty eligible buckets meant ~500 RPC round trips per cycle, up to
	 * {@code concurrentTasks} of them running at once - one to two orders of magnitude more than the
	 * {@code PingRefreshTask} that routing-table maintenance would spend on the same bucket.
	 * </p>
	 * <p>
	 * <b>What the bound costs.</b> Latency in filling the table, never completeness. Buckets over the
	 * budget are not dropped: they are simply not refreshed this time, stay stale, and therefore sort to
	 * the front of the next bootstrap's selection - the deferred ones rotate in without any extra state
	 * being kept. Combined with the per-bucket rate limit, a bucket is filled at most once per
	 * {@link KadConstants#BUCKET_REFRESH_INTERVAL} in steady state, which for realistic table sizes
	 * still covers every bucket within a refresh interval.
	 * </p>
	 * <p>
	 * <b>Effect on startup, which is the case that most needs to stay fast.</b> On a cold start the
	 * budget does not bind: {@link #fillHomeBucket} runs first, and by the time this is reached the
	 * table is a handful of buckets. On a restart from a cached routing table it does bind, and binding
	 * makes startup faster rather than slower. {@code RoutingTable.load()} does not stamp the loaded
	 * buckets, so all of them read as stale; {@link #deploy()} has already queued one
	 * {@code PingRefreshTask} per loaded bucket, and the first bootstrap still enqueues at the head of
	 * the queue. Without a budget that is a full set of expensive lookups preempting a full set of cheap
	 * pings for the same slots - and the pings are what actually make the table usable, since they
	 * revalidate contacts already known to be close. Capping the lookups leaves them room. The returned
	 * future also gates bootstrap completion, and on startup the connection status behind it, so fewer
	 * lookups to wait on means reaching {@code Connected} sooner as well.
	 * </p>
	 *
	 * @return a future completing when every dispatched lookup has finished.
	 */
	private Future<Void> fillBuckets() {
		List<KBucket> buckets = selectBucketsToFill(initialBootstrap);
		if (buckets.isEmpty())
			return Future.succeededFuture();

		List<Future<Void>> futures = new ArrayList<>(buckets.size());
		for (KBucket bucket : buckets) {
			Promise<Void> promise = Promise.promise();
			// Stamped before the lookup runs, not after it succeeds: this is a rate limiter, and a
			// lookup that hangs or finds nothing must not be re-dispatched on the next bootstrap.
			// Note this is the fill path's own clock - deliberately not KBucket.updateRefreshTime(),
			// which belongs to the cheaper ping-refresh path and would be muted by an optimistic
			// stamp here. See the two fields on KBucket.
			bucket.updateLookupRefreshTime();
			NodeLookupTask task = new NodeLookupTask(kadContext, bucket.prefix().createRandomId())
					.setName("Bootstrap: filling Bucket - " + bucket.prefix())
					.addListener(t -> promise.complete());
			taskManager.add(task, initialBootstrap);

			futures.add(promise.future());
		}

		return Future.all(futures).mapEmpty();
	}

	/**
	 * Chooses which buckets this bootstrap will top up with an iterative lookup.
	 * <p>
	 * Package-private and free of side effects so the selection can be tested without a live network;
	 * {@code initial} is a parameter rather than a read of {@link #initialBootstrap} for the same
	 * reason.
	 * </p>
	 *
	 * @param initial true if this is the first bootstrap since startup.
	 * @return the buckets to fill, at most {@link KadConstants#MAX_BUCKET_FILLS_PER_BOOTSTRAP} of them,
	 * 		   most overdue first.
	 */
	List<KBucket> selectBucketsToFill(boolean initial) {
		// Below this many contacts the node cannot reach the network unaided, and a random-id lookup
		// into a sparse bucket has nothing to route through - the budget belongs to fillHomeBucket,
		// the one lookup seeded with the configured bootstrap nodes, which is what can reconnect it.
		//
		// Waived for the first bootstrap after startup. The gate's premise is that this is routine
		// maintenance on a node already serving traffic; on a cold start neither half holds, the table
		// is small by definition, and time to a usable table is what matters most.
		if (!initial && routingTable.getNumberOfEntries() < useBootstrapNodesThreshold)
			return List.of();

		List<KBucket> candidates = new ArrayList<>(routingTable.size());
		routingTable.forEachBucket(bucket -> {
			// An empty bucket is normally an artifact of deep splitting - it covers a slice of the
			// keyspace that holds no reachable nodes - so a lookup there converges on nothing and
			// would repeat, at the full cost of an iterative lookup, on every bootstrap forever.
			if (bucket.isEmpty())
				return;

			// A full bucket has nothing to gain, unless the table as a whole is thin enough that we
			// want contacts everywhere we can get them.
			if (bucket.isFull() && routingTable.getNumberOfEntries() >= bootstrapThreshold)
				return;

			// Per-bucket rate limit. Bootstrap runs as often as every BOOTSTRAP_INTERVAL when the
			// table is below the bootstrap threshold, which without this would re-run the same
			// iterative lookups on the same buckets every few minutes.
			if (!bucket.needsLookupRefresh())
				return;

			// The cheap path is already repairing this bucket; stacking an iterative lookup on top of
			// an in-flight PingRefreshTask spends far more to answer the same question.
			if (maintenanceTasks.containsKey(bucket))
				return;

			candidates.add(bucket);
		});

		if (candidates.size() <= KadConstants.MAX_BUCKET_FILLS_PER_BOOTSTRAP)
			return candidates;

		// Most overdue first, emptiest as the tiebreak - which is also the order on a restart from a
		// cached routing table, where every bucket reads as equally stale. Filling a bucket stamps it,
		// so it sinks to the back of the next selection: round-robin over the buckets that miss the
		// budget falls out of the sort, with no extra state to keep.
		candidates.sort(Comparator.comparingLong(KBucket::lastLookupRefresh)
				.thenComparing(Comparator.comparingInt(KBucket::deficit).reversed()));
		return candidates.subList(0, KadConstants.MAX_BUCKET_FILLS_PER_BOOTSTRAP);
	}

	/**
	 * Revalidates a routing table loaded from the persist file, and reports whether any of it is still
	 * alive.
	 * <p>
	 * <b>What the returned future means.</b> Not "the sweep is finished" - "the cached table has been
	 * shown to be usable, or shown not to be". It resolves on the <em>first</em> answered ping, which
	 * is simultaneous proof that the socket works and that at least one cached contact is still there;
	 * failing that, when every dispatched task has settled.
	 * </p>
	 * <p>
	 * <b>Why the distinction is worth the plumbing.</b> Until this sweep answered, nothing else did:
	 * {@code RpcServer.reachable} starts out {@code true} and only notifies on a change, so the
	 * reachable handler is silent at startup, leaving {@code Future.all(connectFutures)} as the sole
	 * exit from {@code Connecting}. Waiting on the last answer rather than the first is the same defect
	 * {@link #askBootstrapNodes} had, and it costs the same ten seconds. A cached contact that has gone
	 * away is not refused, it is simply silent, so its
	 * ping settles only at {@code RPC_CALL_TIMEOUT_MAX} - and a freshly started node has no RTT samples
	 * yet, so {@code TimeoutSampler} hands out exactly that maximum until something answers. A task
	 * pings {@code alpha} at a time and holds those slots for the full timeout, so any bucket with one
	 * dead contact among its first {@code alpha} entries takes ten seconds to drain no matter how
	 * quickly the rest of it answers. That is nearly every warm start.
	 * </p>
	 * <p>
	 * The cost is bounded by the timeout, not multiplied by the bucket, because the first response also
	 * feeds the sampler and collapses the timeout for everything sent afterwards.
	 * </p>
	 * <p>
	 * <b>The whole table gets covered, a batch at a time.</b> Coverage and concurrency are separate
	 * questions, and only the second one caused the trouble this method was written for: what starves
	 * the bootstrap is claiming every runner at once, not continuing to work afterwards.
	 * {@code TaskManager} runs at most {@code concurrentTasks} and does not preempt, so the sweep holds
	 * at most half of them and refills as tasks finish, until every loaded bucket has been revalidated
	 * once. That is what lets the whole cache be purged rather than the front of it, and it is why no
	 * bucket is left to the maintenance path to finish - a partly-cleaned cache is the worst of both.
	 * </p>
	 * <p>
	 * The tail is cheap in the case that matters. A dead contact costs the full RPC timeout only until
	 * something answers; after that the sampler recalibrates and later batches drain in milliseconds.
	 * A live cache is therefore swept in about as long as it takes to hear back once, and only a cache
	 * that is dead all the way through pays the timeout per batch - a node with nothing better to do.
	 * </p>
	 * <p>
	 * <b>Resolving early abandons nothing.</b> The returned future settles as soon as a batch's worth of
	 * tasks has finished, not when the sweep has, so the status decision never waits on coverage it does
	 * not need; the rest carries on detached. Nothing is cancelled, and every dead contact is still
	 * pinged and purged.
	 * </p>
	 *
	 * @return a future resolving when the cached table has proved itself, or when a batch's worth of
	 * 		   tasks has given up on it.
	 */
	private Future<Void> pingRoutingTable() {
		List<KBucket> buckets = selectBucketsToPing();
		if (buckets.isEmpty())
			return Future.succeededFuture();

		Promise<Void> promise = Promise.promise();

		// Half the slots, so the bootstrap queued beside this always has a runner. The other startup
		// work fits in what is left: at the default 32 that is 16 here, up to
		// MAX_BUCKET_FILLS_PER_BOOTSTRAP for fillBuckets and one for the home-bucket fill. All of them
		// come out of the same concurrentTasks, so a reader retuning it should retune them together.
		int batch = Math.min(Math.max(1, concurrentTasks / 2), buckets.size());

		// Single-threaded by context confinement; the holders exist so the listeners can capture and
		// mutate them, not for thread safety. `next` is the read cursor into buckets. `pending` counts
		// down a batch's worth of finished tasks and is then cleared for good - the fallback the status
		// decision falls back on when nothing ever answers.
		Variable<Integer> next = Variable.of(0);
		Variable<Integer> pending = Variable.of(batch);

		// Reported the moment a cached contact answers, rather than left to the combinator that waits on
		// this future. The combinator also waits on the bootstrap, and on the warm start that matters -
		// the one where the cache went stale while the node was down - the bootstrap is the slower of
		// the two, since its lookups route through the same dead contacts this sweep is pinging.
		// Resolving the sweep alone would therefore change nothing observable. Announcing the evidence
		// directly is what the reachability handler does with the same kind of evidence, and it can only
		// move Connected earlier: the combinator still runs and still has the final say.
		Runnable answered = () -> {
			setStatus(ConnectionStatus.Connected);
			promise.tryComplete();
		};

		// Dispatches one bucket and, when its task ends, the next one still waiting - so the number in
		// flight stays at the batch size until the list runs out. Recursion is bounded by the task
		// lifecycle rather than the stack: the listener fires from the event loop, not from here.
		Variable<Runnable> dispatch = Variable.empty();
		dispatch.set(() -> {
			int index = next.updateAndGet(v -> v + 1) - 1;
			if (index >= buckets.size())
				return;

			KBucket bucket = buckets.get(index);
			// checkAll, even though removeOnTimeout already queues every entry: without it iterate()
			// re-tests each one with needsPing() and skips it, so what actually got pinged was whatever
			// that allowed - nothing seen inside 30 seconds, nothing silent for less than
			// OLD_AND_STALE_TIME. A restart inside that window therefore revalidated none of its cache
			// and reported Connected off entries no one had spoken to since the process died.
			//
			// This is not a wider eviction policy, only a consistent one. A node that has been down
			// longer than OLD_AND_STALE_TIME already had needsPing() true for every cached entry, with
			// any pre-shutdown backoff long expired, so ping-everything-and-purge-on-timeout was the
			// behaviour for every ordinary warm start; checkAll changes only the fast restart, which had
			// been getting no validation at all. How long the process happened to be down is not
			// evidence about whether its contacts are still there.
			//
			// The NAT-binding reasoning behind the 30-second rule in needsPing does not reach here
			// either: it exists to let bindings expire in steady state, and a node that has just started
			// holds a fresh socket with no prior bindings to keep alive.
			PingRefreshTask task = new PingRefreshTask(kadContext)
					.setName("Bootstrap: ping cached routingtable - " + bucket.prefix())
					.checkAll(true)
					.removeOnTimeout(true)
					.bucket(bucket)
					.onFirstResponse(answered)
					.addListener(t -> {
						// A batch's worth of finished tasks is enough to answer the status question -
						// whichever tasks they turn out to be, not the opening batch specifically, so one
						// slow bucket cannot hold the status while equivalent work has already settled.
						// Waiting for the whole sweep instead would keep a node in Connecting for as long
						// as its cache takes to clean, which on an all-dead cache is minutes.
						if (pending.isPresent() && pending.updateAndGet(v -> v - 1) == 0) {
							pending.clear();
							promise.tryComplete();
						}

						dispatch.get().run();
					});
			taskManager.add(task);
		});

		for (int i = 0; i < batch; i++)
			dispatch.get().run();

		return promise.future();
	}

	/**
	 * Chooses which cached buckets the warm start revalidates now.
	 * <p>
	 * Package-private and free of side effects so the selection can be tested without a live network,
	 * the same shape as {@link #selectBucketsToFill} and {@link #selectBootstrapNodes}.
	 * </p>
	 * <p>
	 * <b>Every non-empty bucket, in the order to take them</b> - the whole loaded table is revalidated,
	 * so what this decides is priority, not membership. The bound that matters is on how many are in
	 * flight at once, and that belongs to {@link #pingRoutingTable}, which dispatches from this list a
	 * batch at a time: a sweep starves the bootstrap by claiming every runner, not by continuing to
	 * work. Keeping the two apart is what lets the whole cache be purged instead of the front of it.
	 * </p>
	 * <p>
	 * <b>Ordering.</b> Closest to home first, by XOR distance from our own id to the bucket's prefix.
	 * Staleness cannot discriminate here - {@code load()} does not stamp the buckets it reads, so on a
	 * warm start they are all equally stale - and closeness is what should be validated first, since
	 * the buckets nearest us hold the contacts a lookup actually routes through.
	 * </p>
	 *
	 * @return every non-empty bucket, closest to home first.
	 */
	List<KBucket> selectBucketsToPing() {
		List<KBucket> candidates = new ArrayList<>(routingTable.size());
		routingTable.forEachBucket(bucket -> {
			// A task built from an empty bucket has an empty todo queue: it would be dispatched, occupy
			// a slot, ping nobody and finish. The inline loop this replaced did exactly that.
			if (!bucket.isEmpty())
				candidates.add(bucket);
		});

		// XOR distance from our own id to the bucket's prefix, nearest first - the same ordering the
		// lookup layer uses for nodes, applied to prefixes, which Prefix supports by extending Id.
		//
		// Deliberately not prefix depth, which looks like a proxy for closeness and is not one. Depth
		// counts a prefix's fixed bits; it says nothing about whether those bits match ours. That would
		// be equivalent under classic Kademlia, where only the home bucket ever splits and the tree is
		// a caterpillar, but needsSplit() here splits any full bucket whose new entry lands in the high
		// branch, so a far branch can be deeper than the home bucket. Sorting by depth would then put
		// buckets covering keyspace nowhere near us at the front of the sweep.
		//
		// The home bucket needs no special case: its prefix is a prefix of our id, so it differs from
		// us only below its own depth, while every other bucket differs at a more significant bit. It
		// sorts first on its own.
		Id localId = identity.getId();
		candidates.sort((a, b) -> localId.threeWayCompare(a.prefix(), b.prefix()));
		return candidates;
	}

	private void tryPingMaintenance(KBucket bucket, boolean checkAll, boolean removeOnTimeout, boolean probeReplacement, String name) {
		if (!rpcServer.isReachable())
			return;

		if (maintenanceTasks.containsKey(bucket))
			return;

		boolean refreshNeeded = bucket.needsToBeRefreshed();
		boolean replacementNeeded = bucket.needsReplacementPing() || (bucket.isHomeBucket() && bucket.findPingableReplacement() != null);
		if ((refreshNeeded || replacementNeeded) && !maintenanceTasks.containsKey(bucket)) {
			PingRefreshTask task = new PingRefreshTask(kadContext)
					.setName(name)
					.checkAll(checkAll)
					.removeOnTimeout(removeOnTimeout)
					.probeReplacement(probeReplacement)
					.bucket(bucket);

			// The entry is claimed before the task is handed over and released by the task's own
			// listener, so this depends on TaskManager always driving a task it accepts to a terminal
			// state - including the paths where it rejects one. A task that ends silently would strand
			// the entry, and an entry here excludes its bucket from the ping path above and from
			// selectBucketsToFill as well, so the bucket would never be refreshed again by either.
			if (maintenanceTasks.putIfAbsent(bucket, task) == null) {
				task.addListener(t -> maintenanceTasks.remove(bucket, task));
				taskManager.add(task);
			}
		}
	}

	private void setStatus(ConnectionStatus status) {
		if (this.status == status) // nothing changed
			return;

		ConnectionStatus old = this.status;
		this.status = status;

		log.info("DHT {}:{} connection status changed from {} to {}", network, identity.getId(), old, status);

		if (connectionStatusListener == null)
			return;

		switch (status) {
			case Connecting -> connectionStatusListener.connecting(network);
			case Connected -> connectionStatusListener.connected(network);
			case Disconnected -> connectionStatusListener.disconnected(network);
			default -> {}
		}
	}

	@SuppressWarnings("unchecked")
	private void onMessage(Message message) {
		if (!isRunning())
			return;

		// ignore the messages from myself
		if (message.getId().equals(identity.getId()))
			return;

		switch (message.getType()) {
			case REQUEST -> onRequest(message);
			case RESPONSE -> onResponse(message);
			case ERROR -> onError(message);
		}

		received(message);
	}

	@SuppressWarnings("unchecked")
	private void onRequest(Message message) {
		switch (message.getMethod()) {
			case PING -> onPing(message);
			case FIND_NODE -> onFindNode(message);
			case FIND_VALUE -> onFindValue(message);
			case STORE_VALUE -> onStoreValue(message);
			case FIND_PEER -> onFindPeer(message);
			case ANNOUNCE_PEER -> onAnnouncePeer(message);
			default -> onUnknownMethod(message);
		}
	}

	private void onPing(Message request) {
		Message response = Message.pingResponse(request.getTxid())
				.setRemote(request.getRemoteId(), request.getRemoteAddress());
		rpcServer.sendMessage(response);
	}

	/**
	 * Sends a response message.
	 * <p>
	 * The DHT can be undeployed while a response is still being assembled, which clears
	 * {@code rpcServer}; drop the response in that case rather than failing on the event loop.
	 * </p>
	 *
	 * @param response the response to send.
	 * @return a future that completes when the response is sent, or fails if it was dropped.
	 */
	private Future<Void> sendResponse(Message response) {
		if (!running || rpcServer == null) {
			log.debug("DHT {}:{} stopped while assembling a response, dropping it", network, identity.getId());
			return Future.failedFuture(new IllegalStateException("DHT is not running"));
		}

		return rpcServer.sendMessage(response);
	}

	/**
	 * Returns how many nodes per address family a response may carry, given which families the
	 * requester asked for.
	 * <p>
	 * <b>What this bounds and why it is not just k.</b> k says how many contacts a routing bucket
	 * keeps; it is a routing-robustness knob and has nothing to do with what fits in a datagram.
	 * Returning k nodes couples the two, so a node raising k for better routing would silently start
	 * emitting oversized packets. The count is therefore
	 * {@code min(k, MAX_NODES_PER_RESPONSE, whatever the packet budget allows)}.
	 * </p>
	 * <p>
	 * <b>Why the packet budget matters more than the declared cap.</b> A response that exceeds the
	 * path MTU is fragmented, and a fragmented UDP datagram is lost entirely if any one fragment is
	 * lost - plus middleboxes commonly drop fragments outright. So overshooting the MTU does not
	 * degrade gradually, it turns a working lookup into a silent black hole on some paths. The
	 * declared cap alone is not enough to prevent this: at k=16 a dual-family response is roughly
	 * {@code 24 + 16*44.5 + 16*56.5 + 48} bytes, about 1690, which exceeds both
	 * {@link Network#maxPacketSize()} budgets (1450 for IPv4, 1200 for IPv6). The single-family cases
	 * fit comfortably; it is specifically {@code want4 && want6} that overflows, which is why the
	 * budget is split across the families actually requested rather than applied per family.
	 * </p>
	 * <p>
	 * <b>Resulting numbers</b> at the default k=16, using the per-entry estimates in
	 * {@link KadConstants}: 16 for a single family (the declared cap binds first), about 12 per family
	 * for a dual-family response over an IPv4 socket, and about 9 over an IPv6 socket, whose MTU
	 * budget is smaller. That lands in the same place as Ethereum's discv4, which fits roughly 12
	 * nodes per Neighbors packet under an equivalent constraint.
	 * </p>
	 * <p>
	 * <b>Trade-off.</b> Fewer nodes per response means more lookup rounds and so higher latency,
	 * since convergence is O(log_k N) in the count actually returned rather than in the configured k.
	 * More nodes means larger datagrams and, past the MTU, catastrophic rather than gradual loss. The
	 * asymmetry is the whole argument for erring low.
	 * </p>
	 * <p>
	 * This is not a protocol rule - the protocol sets no minimum, and a requester must already cope
	 * with receiving fewer nodes than it asked for, because a small routing table returns fewer. It is
	 * a transport-driven implementation limit.
	 * </p>
	 *
	 * @param want4 whether the requester asked for IPv4 nodes.
	 * @param want6 whether the requester asked for IPv6 nodes.
	 * @return the maximum node count per requested family; 0 if neither family was requested.
	 */
	private int nodesPerFamily(boolean want4, boolean want6) {
		if (!want4 && !want6)
			return 0;

		// Cost of one node of each family the requester actually asked for. A dual-family response
		// pays both per slot, which is why it runs out of budget at roughly half the count.
		int perSlot = (want4 ? KadConstants.NODE_ENTRY_SIZE_V4 : 0) +
				(want6 ? KadConstants.NODE_ENTRY_SIZE_V6 : 0);

		// The response leaves on this DHT's own socket, so this DHT's family sets the MTU budget -
		// an IPv6 node has less room to work with even when answering with IPv4 nodes.
		int budget = network.maxPacketSize() - KadConstants.RESPONSE_OVERHEAD;
		int fits = budget / perSlot;

		return Math.max(1, Math.min(Math.min(k, KadConstants.MAX_NODES_PER_RESPONSE), fits));
	}

	private void onFindNode(Message request) {
		FindNodeRequest body = request.getBody();
		Id target = body.getTarget();
		int want = nodesPerFamily(body.doesWant4(), body.doesWant6());
		int want4 = body.doesWant4() ? want : 0;
		int want6 = body.doesWant6() ? want : 0;

		populateClosestNodes(target, want4, want6).onSuccess(closest -> {
			int token = body.doesWantToken() ?
					tokenManager.generateToken(request.getId(), request.getRemoteAddress(), target) : 0;

			Message response = Message.findNodeResponse(request.getTxid(), closest.nodes4, closest.nodes6, token)
					.setRemote(request.getId(), request.getRemoteAddress());
			sendResponse(response);
		}).onFailure(cause ->
				log.error("Failed to populate the closest nodes for FIND NODE request from {}",
						request.getRemoteAddress(), cause)
		);
	}

	private void onFindValue(Message request) {
		FindValueRequest body = request.getBody();
		Id target = body.getTarget();
		int expectedSequenceNumber = body.getExpectedSequenceNumber();
		storage.getValue(target).compose(value -> {
			if (value != null && (!value.isMutable() || expectedSequenceNumber < 0 ||
					value.getSequenceNumber() >= expectedSequenceNumber))
				return Future.succeededFuture(Message.findValueResponse(request.getTxid(), value));

			int want = nodesPerFamily(body.doesWant4(), body.doesWant6());
			int want4 = body.doesWant4() ? want : 0;
			int want6 = body.doesWant6() ? want : 0;
			return populateClosestNodes(target, want4, want6).map(closest ->
					Message.findValueResponse(request.getTxid(), closest.nodes4, closest.nodes6));
		}).transform(ar -> {
			Message response = ar.succeeded() ? ar.result() :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return sendResponse(response);
		});
	}

	private void onStoreValue(Message request) {
		StoreValueRequest body = request.getBody();

		kadContext.executeBlocking(() -> {
			Value value = body.getValue();

			if (!tokenManager.verifyToken(body.getToken(), request.getId(),
					request.getRemoteAddress(), value.getId())) {
				log.warn("Received a store value request with invalid token from {}", request.getRemoteAddress());
				throw new InvalidTokenException("Invalid token for STORE VALUE request");
			}

			if (!value.isValid())
				throw new InvalidValueException("Invalid value for STORE VALUE request");

			return value;
		}).compose(value ->
			// Atomic validate-and-store: existence check + immutable/CAS/owner validation + write in one
			// transaction (see DataStorage#putValue). failIfNotOwner=false: keep our own value on conflict.
			storage.putValue(value, body.getExpectedSequenceNumber(), false, false)
		).transform(ar -> {
			Message response = ar.succeeded() ? Message.storeValueResponse(request.getTxid()) :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return rpcServer.sendMessage(response);
		});
	}

	private void onFindPeer(Message request) {
		FindPeerRequest body = request.getBody();
		Id target = body.getTarget();
		int expectedSequenceNumber = body.getExpectedSequenceNumber();
		int expectedCount = body.getExpectedCount() > 0 ? body.getExpectedCount() : 16;
		storage.getPeers(target, expectedSequenceNumber, expectedCount).compose(peers -> {
			if (!peers.isEmpty())
				return Future.succeededFuture(Message.findPeerResponse(request.getTxid(), peers));

			int want = nodesPerFamily(body.doesWant4(), body.doesWant6());
			int want4 = body.doesWant4() ? want : 0;
			int want6 = body.doesWant6() ? want : 0;
			return populateClosestNodes(target, want4, want6).map(closest ->
					Message.findPeerResponse(request.getTxid(), closest.nodes4, closest.nodes6));
		}).transform(ar -> {
			Message response = ar.succeeded() ? ar.result() :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return sendResponse(response);
		});
	}

	private void onAnnouncePeer(Message request) {
		AnnouncePeerRequest body = request.getBody();
		InetAddress remoteAddress = request.getRemoteIpAddress();
		boolean allowed = kadContext.isDeveloperMode() ?
				AddressUtils.isAnyUnicast(remoteAddress) : AddressUtils.isGlobalUnicast(remoteAddress);
		if (!allowed) {
			log.debug("Received an announce peer request from unsupported address {}, ignored",
					request.getRemoteAddress());
			return;
		}

		kadContext.executeBlocking(() -> {
			PeerInfo peer = body.getPeer();

			if (!tokenManager.verifyToken(body.getToken(), request.getId(),
					request.getRemoteAddress(), peer.getId())) {
				log.warn("Received a announce peer request with invalid token from {}", request.getRemoteAddress());
				throw new InvalidTokenException("Invalid token for ANNOUNCE PEER request");
			}

			if (!peer.isValid())
				throw new InvalidPeerException("Invalid value for ANNOUNCE PEER request");

			return peer;
		}).compose(peer ->
			// Atomic validate-and-store (see DataStorage#putPeer). failIfNotOwner=false: keep our own peer on conflict.
			storage.putPeer(peer, body.getExpectedSequenceNumber(), false, false)
		).transform(ar -> {
			Message response = ar.succeeded() ? Message.announcePeerResponse(request.getTxid()) :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return rpcServer.sendMessage(response);
		});
	}

	private void onUnknownMethod(Message request) {
		Message response = Message.error(request.getMethod(), request.getTxid(), ErrorCode.MethodUnknown.value(),
				"Unknown method: " + request.getMethod());
		response.setRemote(request.getId(), request.getRemoteAddress());
		rpcServer.sendMessage(response);
	}

	@SuppressWarnings("unused")
	private void onResponse(Message response) {
		// Nothing to do
	}

	private void onError(Message error) {
		Error body = error.getBody();
		log.warn("Error from {}/{} - {}:{}, method {}, txid {}", error.getRemoteAddress(), error.getReadableVersion(),
				body.getCode(), body.getMessage(), error.getMethod(), error.getTxid());
	}

	/**
	 * Increase the failed queries count of the bucket entry we sent the message to.
	 *
	 * @param call the RPC call.
	 */
	private void onTimeout(RpcCall call) {
		// ignore the timeout if the DHT is stopped or the RPC server is offline
		if (!isRunning() || !rpcServer.isReachable())
			return;

		Id nodeId = call.getTargetId();
		routingTable.onTimeout(nodeId);
	}

	protected void onSend(RpcCall call) {
		if (!isRunning())
			return;

		Id nodeId = call.getTargetId();
		routingTable.onRequestSent(nodeId);
	}

	private Message exceptionToError(Message.Method method, long txid, Throwable cause) {
		int code;
		String msg;

		if (cause instanceof KadException error) {
			code = error.getCode();
			msg = error.getMessage();
		} else {
			code = ErrorCode.GenericError.value();
			msg = "Node internal error";
		}

		return Message.error(method, txid, code, msg);
	}

	private void received(Message message) {
		InetAddress remoteAddress = message.getRemoteIpAddress();
		int remotePort = message.getRemotePort();
		boolean allowed = kadContext.isDeveloperMode() ?
				AddressUtils.isAnyUnicast(remoteAddress) : AddressUtils.isGlobalUnicast(remoteAddress);
		if (!allowed) {
			log.warn("Received a message from unsupported address {}, ignored the potential routing table update",
					message.getRemoteAddress());
			return;
		}

		// we only want consistent nodes in our routing table,
		// so apply a stricter check here

		RpcCall call = message.getAssociatedCall();
		if (call != null && (call.isIdMismatched() || call.isAddressMismatched())) {
			// this might happen if one node changes ports (broken NAT?) or IP address
			// ignore until routing table entry times out
			log.warn("Received a message from inconsistent node {}@{}, ignored the potential routing table update",
					message.getId(), message.getRemoteAddress());
			suspiciousNodeDetector.inconsistent(message.getRemoteAddress(), message.getId());
			return;
		}

		Id id = message.getId();
		Id knownId = suspiciousNodeDetector.lastKnownId(message.getRemoteAddress());
		if (knownId != null && !knownId.equals(id)) {
			// We already know a node with that address but with a different ID.
			// This might happen if one node changes its ID.
			// Force remove from the routing table to prevent suspicious behavior
			log.warn("Received a message from suspicious node {}@{}, force-removing routing table entries because ID-change was detected; new ID {}",
					message.getId(), message.getRemoteAddress(), knownId);

			if (routingTable.remove(knownId)) {
				// Might be a pollution attack, check other entries in the same bucket too.
				// In case the random pings can't keep up with scrubbing.
				KBucket bucket = routingTable.bucketOf(knownId);
				// noinspection LoggingSimilarMessage
				log.info("Checking bucket {} after ID change was detected", bucket.prefix());
				tryPingMaintenance(bucket, true, false, false,
						"Checking bucket " + bucket.prefix() + " after ID change was detected");
			}

			if (routingTable.remove(id)) {
				// Might be a pollution attack, check other entries in the same bucket too.
				// In case the random pings can't keep up with scrubbing.
				KBucket bucket = routingTable.bucketOf(id);
				// noinspection LoggingSimilarMessage
				log.info("Checking bucket {} after ID change was detected", bucket.prefix());
				tryPingMaintenance(bucket, true, false, false,
						"Checking bucket " + bucket.prefix() + " after ID change was detected");
			}

			suspiciousNodeDetector.inconsistent(message.getRemoteAddress(), message.getId());
			return;
		}

		KBucketEntry existing = routingTable.getEntry(id, true);
		if (existing != null && (!existing.getIpAddress().equals(remoteAddress) ||
				existing.getPort() != remotePort)) {
			// this might happen if one node changes ports (broken NAT?) or IP address
			// ignore until routing table entry times out
			log.warn("Received a message from inconsistent node {}@{}, ignored the potential routing table update",
					message.getId(), message.getRemoteAddress());

			suspiciousNodeDetector.inconsistent(message.getRemoteAddress(), message.getId());
			return;
		}

		suspiciousNodeDetector.observe(message.getRemoteAddress(), message.getId());
		KBucketEntry newEntry = new KBucketEntry(id, new InetSocketAddress(remoteAddress, remotePort));
		newEntry.setVersion(message.getVersion());

		if (call != null) {
			newEntry.onResponded(call.getRTT());
			newEntry.updateLastSent(call.getSentTime());
		}

		routingTable.put(newEntry);

		// Optimize: not the standard Kademlia behavior
		// incoming request && the new entry is unreachable && the target bucket not full,
		// then try to do a ping request to the new entry check its availability.
		if (existing == null && !newEntry.isReachable()) {
			// Verify the node, speed up the bootstrap process or make the bucket more reliable.
			// only if the new entry is unreachable and the bucket is not full yet
			Message request = Message.pingRequest();
			RpcCall ping = new RpcCall(newEntry, request);
			rpcServer.sendCall(ping);
		}
	}

	/**
	 * Collects the closest nodes to the target from this DHT's own routing table.
	 * <p>
	 * The routing table and its entries are single-threaded state owned by this verticle, so this
	 * must only be called on this DHT's context - see {@link #populateClosestNodes}, which hops to
	 * the sibling's context before calling it there.
	 * </p>
	 *
	 * @param target the lookup target.
	 * @param want   the number of nodes to collect.
	 * @return the closest nodes, including this node itself when the table cannot fill the request.
	 */
	private List<NodeInfo> collectClosestNodes(Id target, int want) {
		List<NodeInfo> nodes = routingTable.getClosestNodes(target, want)
				.includeReplacements(routingTable.getNumberOfEntries() < want)
				.fill()
				.nodes();

		// Add self to the list if needed
		if (nodes.size() < want)
			nodes.add(nodeInfo);

		return nodes;
	}

	/**
	 * Collects the closest nodes to the target for both address families.
	 * <p>
	 * The local family is collected inline on this DHT's context. The sibling family, if wanted and
	 * a sibling is wired, is collected on the sibling's own context: its routing table is
	 * single-threaded state that must not be walked from here. The result is normalized to plain
	 * {@link NodeInfo} so no mutable {@code KBucketEntry} crosses event loops.
	 * </p>
	 * <p>
	 * When there is no sibling, or only the local family is wanted, this completes synchronously -
	 * {@link Future#succeededFuture} carries no context, so its handlers run inline and no context
	 * switch is paid on the common single-stack path.
	 * </p>
	 *
	 * @param target the lookup target.
	 * @param v4     the number of IPv4 nodes wanted, or 0.
	 * @param v6     the number of IPv6 nodes wanted, or 0.
	 * @return a future, completing on this DHT's context, with the closest nodes of both families.
	 */
	Future<ClosestNodes> populateClosestNodes(Id target, int v4, int v6) {
		final boolean localIsV4 = network == Network.IPv4;
		final int localWant = localIsV4 ? v4 : v6;
		final int siblingWant = localIsV4 ? v6 : v4;

		final List<NodeInfo> localNodes = localWant > 0 ? collectClosestNodes(target, localWant) : List.of();

		final DHT sibling = this.sibling;
		// The sibling is wired before either DHT deploys, so it may not be running yet. isRunning()
		// is volatile and is set after prepare(), so reading it true also publishes its vertxContext.
		if (siblingWant <= 0 || sibling == null || !sibling.isRunning())
			return Future.succeededFuture(closestNodes(localIsV4, localNodes, List.of()));

		// The sibling owns its routing table, so collect there and hand back immutable NodeInfo.
		// The promise is bound to our context, so the continuation resumes on this event loop.
		Promise<List<NodeInfo>> promise = promise();
		sibling.runOnContext(v -> {
			try {
				// The sibling may have been undeployed while this task was queued.
				promise.complete(sibling.isRunning() ?
						sibling.collectClosestNodes(target, siblingWant).stream()
								.map(n -> NodeInfo.of(n.getId(), n.getAddress4(), n.getAddress6()))
								.toList() :
						List.of());
			} catch (Throwable t) {
				// Never leave the promise pending: the caller would never answer the request.
				promise.fail(t);
			}
		});

		return promise.future().map(siblingNodes -> closestNodes(localIsV4, localNodes, siblingNodes));
	}

	private static ClosestNodes closestNodes(boolean localIsV4, List<NodeInfo> local, List<NodeInfo> sibling) {
		return localIsV4 ? new ClosestNodes(local, sibling) : new ClosestNodes(sibling, local);
	}

	public Future<@Nullable NodeInfo> findNode(Id id, LookupOption option) {
		Promise<NodeInfo> promise = Promise.promise();

		runOnContext(v -> {
			NodeInfo node = routingTable.getEntry(id, true);
			if (option == LookupOption.LOCAL) {
				promise.complete(node);
				return;
			}

			if (node != null && option != LookupOption.CONSERVATIVE) {
				promise.complete(node);
				return;
			}

			NodeLookupTask task = new NodeLookupTask(kadContext, id, option != LookupOption.CONSERVATIVE)
					.setName("Lookup node: " + id)
					.setWantTarget(true)
					.addListener(t ->
							promise.complete(t.getResult())
					);

			taskManager.add(task);
		});

		return promise.future();
	}

	public Future<Value> findValue(Id id, int expectedSequenceNumber, LookupOption option) {
		Promise<Value> promise = Promise.promise();

		runOnContext(v -> {
			ValueLookupTask task = new ValueLookupTask(kadContext, id, expectedSequenceNumber,
					option != LookupOption.CONSERVATIVE)
					.setName("Lookup value: " + id)
					.addListener(t ->
							promise.complete(t.getResult().getValue())
					);

			taskManager.add(task);
		});

		return promise.future();
	}

	public Future<Void> storeValue(Value value, int expectedSequenceNumber) {
		Promise<Void> promise = Promise.promise();

		runOnContext(v -> {
			ValueAnnounceTask announceTask = new ValueAnnounceTask(kadContext, value, expectedSequenceNumber)
					.setName("Store value: " + value.getId())
					.addListener(t -> promise.complete());

			NodeLookupTask lookupTask = new NodeLookupTask(kadContext, value.getId())
					.setWantToken(true)
					.setName("Store value: lookup closest node to - " + value.getId())
					.setNestedTask(announceTask)
					.addListener(t -> {
						if (t.getState() != Task.State.COMPLETED)
							return;

						ClosestSet closest = t.getClosestSet();
						if (closest == null || closest.isEmpty()) {
							// this should never happen
							log.error("!!!INTERNAL ERROR: Value announce task not started because the node lookup task got the empty closest nodes.");
							announceTask.cancel();
							return;
						}

						announceTask.closest(closest);
						taskManager.add(announceTask);
					});

			taskManager.add(lookupTask);
		});

		return promise.future();
	}

	@SuppressWarnings("unused")
	public Future<List<PeerInfo>> findPeer(Id id, int expectedSequenceNumber, int expectedCount, LookupOption option) {
		Promise<List<PeerInfo>> promise = Promise.promise();

		runOnContext(v -> {
			PeerLookupTask task = new PeerLookupTask(kadContext, id, expectedSequenceNumber, expectedCount,
					option != LookupOption.CONSERVATIVE)
					.setName("Lookup peer: " + id)
					.addListener(t -> promise.complete(t.getResult().getPeers()));

			taskManager.add(task);
		});

		return promise.future();
	}

	public Future<Void> announcePeer(PeerInfo peer, int expectedSequenceNumber) {
		Promise<Void> promise = Promise.promise();

		runOnContext(v -> {
			PeerAnnounceTask announceTask = new PeerAnnounceTask(kadContext, peer, expectedSequenceNumber)
					.setName("Announce peer: " + peer.getId())
					.addListener(t -> promise.complete());

			NodeLookupTask lookupTask = new NodeLookupTask(kadContext, peer.getId())
					.setWantToken(true)
					.setName("Announce peer: lookup closest node to - " + peer.getId())
					.setNestedTask(announceTask)
					.addListener(t -> {
						if (t.getState() != Task.State.COMPLETED)
							return;

						ClosestSet closest = t.getClosestSet();
						if (closest == null || closest.isEmpty()) {
							// this should never happen
							log.error("!!!INTERNAL ERROR: Peer announce task not started because the node lookup task got the empty closest nodes.");
							announceTask.cancel();
							return;
						}

						announceTask.closest(closest);
						taskManager.add(announceTask);
					});

			taskManager.add(lookupTask);
		});

		return promise.future();
	}

	public Future<Void> dumpRoutingTable(PrintStream out) {
		Promise<Void> promise = Promise.promise();
		runOnContext(v -> {
			routingTable.dump(out);
			promise.complete();
		});
		return promise.future();
	}
}