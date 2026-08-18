package io.bosonnetwork.kademlia.impl;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.AnnounceFailedException;
import io.bosonnetwork.AnnounceResult;
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
import io.bosonnetwork.kademlia.tasks.AnnounceTask;
import io.bosonnetwork.kademlia.tasks.ClosestSet;
import io.bosonnetwork.kademlia.tasks.EligiblePeers;
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

	// True when this run began by loading a persisted routing table that actually held contacts - not
	// merely that a file was there, and not that the table is non-empty, which it structurally always
	// is. Set in start(), not here, so a redeployed instance re-decides: state whose lifetime is a
	// deployment must not be initialized with the lifetime of the object.
	private boolean loadedRoutingTable;

	private final List<Long> timers;

	private final SuspiciousNodeDetector suspiciousNodeDetector;

	private TaskManager taskManager;

	private final Map<KBucket, Task<?>> maintenanceTasks = new IdentityHashMap<>();

	/**
	 * The endpoint whose identity just changed, as {@code host:port}, or null.
	 * <p>
	 * Armed by {@link #onChurn} and spent by the next {@link #received}. One slot rather than a set, and
	 * cleared unconditionally on the way through rather than only on a match: the RPC server reports churn
	 * synchronously, immediately before dispatching the message that revealed it, so nothing can interleave
	 * - but not every churn report is followed by a dispatch. The wrong-id-in-a-response path answers the
	 * call and returns without dispatching at all. A marker that only cleared on a match would survive that
	 * and swallow an unrelated later message from the same endpoint; clearing on the way through makes
	 * stranding impossible instead of merely unlikely.
	 * </p>
	 */
	private String lastChurnedAddress;

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
		// Read the field once: undeploy clears it, and a second read could see the null.
		RpcServer server = rpcServer;
		return server != null && server.isReachable();
	}

	public RoutingTable getRoutingTable() {
		return routingTable;
	}

	/**
	 * Wires the DHT serving the other address family, so that a lookup on either can answer with nodes
	 * from both.
	 * <p>
	 * The sibling must serve the other family. Nothing downstream re-checks it: the two are read into
	 * fixed IPv4 and IPv6 slots by family, so a same-family sibling would quietly fill the wrong slot
	 * and put IPv4 nodes in the IPv6 half of every response.
	 * </p>
	 *
	 * @param dht the DHT serving the other address family, or null to unwire.
	 * @throws IllegalArgumentException if the given DHT is this one, or serves the same family.
	 */
	public void setSibling(@Nullable DHT dht) {
		if (dht == this)
			throw new IllegalArgumentException("Can not set self as sibling");

		if (dht != null && dht.network == network)
			throw new IllegalArgumentException("Can not set a " + network + " DHT as the sibling of another " + network + " DHT");

		this.sibling = dht;
	}

	public @Nullable DHT getSibling() {
		return sibling;
	}

	SuspiciousNodeDetector getSuspiciousNodeDetector() {
		return suspiciousNodeDetector;
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

		// The cached routing table is a warm start, not a precondition. A node whose cache cannot be read
		// still deploys - it just bootstraps from scratch, which is exactly what an absent file already
		// meant - so the failure is recovered here rather than propagated into the chain below, where it
		// would abort the deployment over an unreadable optimization. An unreadable cache is therefore
		// the same answer as a missing one, zero, and the flag below reads the same either way.
		//
		// The flag has to come from what the load returned, not from the table it loaded into: a table
		// holds one all-covering bucket from construction, so every emptiness test that goes through the
		// table itself answers the wrong question - it reports that a routing table exists, which it
		// always does, rather than that a cached one was restored, which is what the warm-start sweep
		// below is asking.
		Future<Void> loaded;
		if (persistFile != null) {
			log.info("Loading routing table from {} ...", persistFile);
			loaded = loadRoutingTable().otherwise(cause -> {
				log.error("Failed to load routing table from {}", persistFile, cause);
				return 0;
			}).map(loadedEntries -> {
				this.loadedRoutingTable = loadedEntries > 0;
				return null;
			});
		} else {
			this.loadedRoutingTable = false;
			loaded = Future.succeededFuture();
		}

		return loaded.compose(unused -> {
			rpcServer = new RpcServer(kadContext, host, port, blacklist, enableSpamThrottling, metrics);
			rpcServer.setMessageHandler(this::onMessage);
			rpcServer.setCallSentHandler(this::onSend);
			rpcServer.setCallTimeoutHandler(this::onTimeout);
			rpcServer.setChurnHandler(this::onChurn);
			return rpcServer.start();
		}).<Void>map(v -> {
			// Set before anything below can send. The startup bootstrap runs inside this block, and
			// every send is gated on this flag - see sendCall and sendResponse - so leaving it until the
			// andThen below would drop the bootstrap's own calls. They would not fail, they would simply
			// never complete: nothing decrements the outstanding count for a call that was never sent,
			// so the bootstrap promise stays pending, its in-progress flag stays set, and the DHT never
			// leaves Connecting. The socket is open at this point, which is what the flag means.
			running = true;

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
			return null;
		}).andThen(ar -> {
			if (ar.succeeded()) {
				log.info("Started DHT {}:{} on {}:{}.", network, identity.getId(), host, port);
			} else {
				// Covers a throw from the block above, which may have run after the flag was set.
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
		// Runs whether the stop succeeded or not, and does not change its outcome - the table is worth
		// keeping either way, and failing to write it does not make the shutdown a failure. Undeploy
		// does wait for it: the shutdown is not finished while the file it leaves behind is half written.
		}).eventually(this::persistRoutingTableOnShutdown).andThen(ar -> {
			if (ar.succeeded())
				log.info("Stopped DHT {}:{} on {}:{}.", network, identity.getId(), host, port);
			else
				log.error("Failed to stop DHT {}:{} on {}:{}.", network, identity.getId(), host, port, ar.cause());
		});
	}

	/**
	 * Writes the routing table out as the last step of undeploy.
	 * <p>
	 * Failures are logged and swallowed: a table that could not be written costs the next start its warm
	 * start, and nothing else, so it must not turn a clean shutdown into a failed one.
	 * </p>
	 *
	 * @return a future completed once the write has finished, successfully or not.
	 */
	private Future<Void> persistRoutingTableOnShutdown() {
		if (persistFile == null)
			return Future.succeededFuture();

		log.info("Persisting routing table on shutdown...");
		return saveRoutingTable().otherwise(cause -> {
			log.error("Persisting routing table failed", cause);
			return null;
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

	/**
	 * Queues a task, unless this deployment is on its way down.
	 * <p>
	 * Every dispatch goes through here, because a task's listeners run synchronously the moment the
	 * task ends - including when {@link TaskManager#cancelAll()} ends it during undeploy. Listeners
	 * queue follow-up work all over this class: the bootstrap chain advances to its next phase, the
	 * warm-start sweep dispatches the next bucket, a lookup hands over to its announce task. Each of
	 * those re-enters the manager from inside its own cancellation loop, where {@code add} throws
	 * because the manager is canceling, and the loop is iterating the very queue the add would append
	 * to. An NPE against the nulled {@code taskManager} is only the last of those outcomes, reached by
	 * a listener that fires after undeploy rather than during it; the first is a shutdown that throws
	 * before it has cleared its queues.
	 * </p>
	 * <p>
	 * {@code running} is cleared at the top of {@link #undeploy()}, before any of that begins, so a
	 * test of it here keeps the re-entry out. Callers must settle whatever waits on the task when this
	 * returns false - a promise completed by a listener of a task that was never queued would never be
	 * completed at all.
	 * </p>
	 *
	 * @param task  the task to queue.
	 * @param prior true to queue it ahead of the tasks already waiting.
	 * @return true if the task was queued, false if this DHT is stopping.
	 */
	private boolean dispatchTask(Task<?> task, boolean prior) {
		TaskManager tm = taskManager;
		if (!running || tm == null) {
			log.debug("DHT {}:{} is stopping, not dispatching task: {}", network, identity.getId(), task);
			return false;
		}

		tm.add(task, prior);
		return true;
	}

	private boolean dispatchTask(Task<?> task) {
		return dispatchTask(task, false);
	}

	private void randomLookup(long unusedTimerId) {
		if (rpcServer.isReachable()) {
			log.info("Periodic: random lookup ...");
			NodeLookupTask task = new NodeLookupTask(kadContext, Id.random())
					.setName("Periodic: random node Lookup");
			dispatchTask(task);
		} else {
			log.info("Periodic: not performing random lookup, node is unreachable.");
		}
	}

	/**
	 * Pings a random routing table entry, to keep the socket's own reachability observation fresh.
	 * <p>
	 * Deliberately skipped whenever calls are already in flight, which on a busy node means it rarely
	 * runs at all. That is the intent rather than a missed case: the probe exists to produce traffic
	 * when there is none, and pending calls are already producing the evidence it would gather.
	 * </p>
	 *
	 * @param unusedTimerId the periodic timer id, unused.
	 */
	private void randomPing(long unusedTimerId) {
		if (!rpcServer.hasPendingCalls()) {
			log.info("Periodic: random ping...");
			KBucketEntry entry = routingTable.getRandomEntry();
			if (entry != null) {
				Message request = Message.pingRequest();
				RpcCall c = new RpcCall(entry, request);
				sendCallInternal(c);
			}
		} else {
			log.info("Periodic: random ping - skip due to node has pending calls.");
		}
	}

	/**
	 * Periodically writes the routing table out, so that a node that dies without a clean shutdown still
	 * has recent contacts to warm-start from.
	 *
	 * @param unusedTimerId the periodic timer id, unused.
	 */
	private void persistRoutingTable(long unusedTimerId) {
		log.info("Periodic: persisting routing table ...");
		saveRoutingTable().onFailure(cause -> log.error("Can not save the routing table: {}", cause.getMessage(), cause));
	}

	/**
	 * Encodes the routing table and writes it to {@code persistFile}.
	 * <p>
	 * The two halves run in different places on purpose. Encoding walks the buckets and their entries -
	 * single-threaded state owned by this verticle - so it happens here, on this context, before any
	 * worker is involved. Only the file write goes to a worker thread, because that is the part that
	 * blocks. Handing the whole job to {@code executeBlocking} would take the I/O off the event loop by
	 * putting the table traversal on a worker instead, which trades a blocked event loop for a data
	 * race; doing neither would put a file write on the event loop every ten minutes for the life of
	 * the node.
	 * </p>
	 * <p>
	 * The write is staged through a temporary file in the same directory and moved into place
	 * atomically, so an interrupted write cannot leave a truncated table to be read back at the next
	 * start. Callers own the failure policy - both of them log and continue.
	 * </p>
	 * <p>
	 * Only called where {@code persistFile} is known to be set: the periodic timer is registered only
	 * when it is, and the shutdown path checks it first.
	 * </p>
	 *
	 * @return a future completed when the file has been written, failed if it could not be.
	 */
	private Future<Void> saveRoutingTable() {
		byte[] data = routingTable.save();
		// Nothing worth writing, and no worker taken to discover that. Note this leaves any previously
		// written file in place rather than truncating it: a node that empties its table has not learned
		// that its last known contacts are bad, only that it currently has none.
		if (data == null || data.length == 0)
			return Future.succeededFuture();

		return kadContext.executeBlocking(() -> {
			if (Files.exists(persistFile)) {
				if (!Files.isRegularFile(persistFile))
					throw new IOException("Routing table file is not a regular file: " + persistFile);
			} else {
				Files.createDirectories(persistFile.getParent());
			}

			Path tempFile = Files.createTempFile(persistFile.getParent(), persistFile.getFileName().toString(),
					"-" + System.currentTimeMillis());
			try (OutputStream out = Files.newOutputStream(tempFile)) {
				out.write(data);
				out.close();
				Files.move(tempFile, persistFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				// Force delete the tempFile if error occurred
				Files.deleteIfExists(tempFile);
			}

			return null;
		});
	}

	/**
	 * Reads {@code persistFile} and loads it into the routing table.
	 * <p>
	 * The mirror of {@link #saveRoutingTable()}, split the same way and for the same reason: the read
	 * blocks and runs on a worker, the parse inserts into the buckets and runs back here, on the context
	 * that owns them.
	 * </p>
	 * <p>
	 * A missing file is not a failure - it is the normal first start - and neither is a corrupt one,
	 * which {@link RoutingTable#load(byte[])} reports by logging and keeping whatever it could read. The
	 * future fails only if the file exists and cannot be read at all.
	 * </p>
	 *
	 * @return a future carrying how much the cache contributed: the number of entries and replacements
	 *         restored, zero for a missing, empty or unusable file. That count is the answer to whether
	 *         there is anything to warm-start from, which cannot be read back off the table afterwards -
	 *         a table that restored nothing is indistinguishable from a fresh one.
	 */
	private Future<Integer> loadRoutingTable() {
		return kadContext.executeBlocking(() -> {
			if (Files.notExists(persistFile) || !Files.isRegularFile(persistFile))
				return null;
			return Files.readAllBytes(persistFile);
		}).map(serialized -> {
			if (serialized != null && serialized.length > 0)
				return routingTable.load(serialized);
			else
				return 0;
		});
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

			sendCallInternal(call);
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
		if (!dispatchTask(task, initialBootstrap))
			return Future.failedFuture(new IllegalStateException("DHT is not running"));

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
			// Abandon the whole phase rather than the one bucket: the DHT is stopping, and the buckets
			// already dispatched are being cancelled behind us.
			if (!dispatchTask(task, initialBootstrap))
				return Future.failedFuture(new IllegalStateException("DHT is not running"));

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
			// Nothing further to sweep if the DHT is stopping. The promise gates the startup connection
			// status, so it is failed rather than left pending on a task that will never run.
			if (!dispatchTask(task))
				promise.tryFail(new IllegalStateException("DHT is not running"));
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
				// The claim above is released by that listener, which only ever fires for a task the
				// manager accepted. Releasing it here as well is what keeps a refused dispatch from
				// stranding the entry - and a stranded entry excludes its bucket from both refresh
				// paths for the life of the deployment.
				if (!dispatchTask(task))
					maintenanceTasks.remove(bucket, task);
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
		sendResponse(response);
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
		RpcServer server = rpcServer;
		if (!running || server == null) {
			log.debug("DHT {}:{} stopped while assembling a response, dropping it", network, identity.getId());
			return Future.failedFuture(new IllegalStateException("DHT is not running"));
		}

		return server.sendMessage(response);
	}

	/**
	 * Sends an RPC call from this DHT, and the only way to do so from outside it.
	 * <p>
	 * Two things have to be true for a call to be safe to send, and neither is the caller's to check.
	 * The DHT can be undeployed between the moment a caller decides to send and the moment it does -
	 * a task resuming from a timer, an application request crossing threads - and undeploy clears the
	 * RPC server, so the send has to be dropped rather than dereference it. And the RPC server's
	 * pending-call table, outbound throttle and timeout timers are single-threaded state owned by this
	 * verticle's context, so a call arriving on any other thread has to be moved onto that context
	 * before it touches them.
	 * </p>
	 * <p>
	 * The context hop is taken only when the caller is not already on this DHT's context, so the
	 * common case - a task or a handler already running here - still sends inline. The running check
	 * is repeated after the hop, because the queued action can be delivered after an undeploy.
	 * </p>
	 *
	 * @param call the RPC call to send.
	 * @return a future that completes with the call once it is sent, or fails if it was dropped.
	 */
	public Future<RpcCall> sendCall(RpcCall call) {
		RpcServer server = rpcServer;
		if (!running || server == null) {
			//noinspection LoggingSimilarMessage
			log.debug("DHT {}:{} is not running, dropping the RPC call to {}",
					network, identity.getId(), call.getTargetId());
			return Future.failedFuture(new IllegalStateException("DHT is not running"));
		}

		if (Vertx.currentContext() != vertxContext) {
			Promise<RpcCall> promise = promise();
			runOnContext(unused -> sendCallInternal(call).onComplete(promise));
			return promise.future();
		}

		return server.sendCall(call);
	}

	/**
	 * Sends an RPC call from a caller already known to be on this DHT's context.
	 * <p>
	 * Same guard as {@link #sendCall}, without the context check that method exists to make. Callers
	 * inside this class reach the transport from a handler, a timer or a continuation that this
	 * verticle owns, so the check could only ever answer one way for them.
	 * </p>
	 * <p>
	 * <b>CAUTION:</b> this is the one entry point that assumes rather than establishes the context, so
	 * it must not be reached from anywhere that could be running on another thread. The RPC server's
	 * pending-call table is a plain map and its timers are context-bound; calling this from off the
	 * context corrupts them silently rather than failing.
	 * </p>
	 *
	 * <p>
	 * Overridable so a test can observe what the DHT decides to send without a socket under it, the way
	 * {@code Task.sendCall} already is; production code has no reason to.
	 * </p>
	 *
	 * @param call the RPC call to send.
	 * @return a future that completes with the call once it is sent, or fails if it was dropped.
	 */
	Future<RpcCall> sendCallInternal(RpcCall call) {
		RpcServer server = rpcServer;
		if (!running || server == null) {
			//noinspection LoggingSimilarMessage
			log.debug("DHT {}:{} is not running, dropping the RPC call to {}",
					network, identity.getId(), call.getTargetId());
			return Future.failedFuture(new IllegalStateException("DHT is not running"));
		}

		return server.sendCall(call);
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
	 * {@link Network#maxPacketSize()} budgets (1400 for IPv4, 1200 for IPv6). The single-family cases
	 * fit comfortably; it is specifically {@code want4 && want6} that overflows, which is why the
	 * budget is split across the families actually requested rather than applied per family.
	 * </p>
	 * <p>
	 * <b>Resulting numbers</b> at the default k=16, using the per-entry estimates in
	 * {@link KadConstants}: 16 for a single family (the declared cap binds first), about 11 per family
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

	/**
	 * Returns how many peers a FIND_PEER response may select, given the count the requester asked for.
	 * <p>
	 * The requested count arrives straight off the wire and becomes the {@code LIMIT} of a database
	 * query, so an unbounded one lets a 63-byte datagram ask this node to select and serialize every
	 * peer it holds for an id. Because UDP source addresses are unverified, that is both work this node
	 * does for a stranger and a response it can be made to aim at a third party. A local lookup names
	 * its own count too, but a local caller is spending its own node's time on its own behalf; a
	 * requester on the wire is spending someone else's.
	 * </p>
	 * <p>
	 * <b>This bounds the query, not the response.</b> That split is what makes peers different from
	 * nodes: a node entry is fixed-size, so capping the count caps the bytes, but a peer entry carries
	 * a variable-length endpoint and optional extra data. The byte side is {@link #fitPeers}; this is
	 * only the ceiling on how much of the database one request may touch.
	 * </p>
	 *
	 * @param requested the count from the request; zero or negative means unspecified.
	 * @return the number of peers to select, at least one.
	 */
	int peersPerResponse(int requested) {
		// An unspecified count gets the declared cap rather than the old inline 16, which no datagram
		// could ever have carried at ~160 bytes per entry - it was unreachable, not intentional.
		int wanted = requested > 0 ? requested : KadConstants.MAX_PEERS_PER_RESPONSE;

		// Selecting more entries than a datagram could carry is pure database work, so the packet
		// budget bounds the query too, using the smallest entry an answer could consist of.
		int budget = network.maxPacketSize() - KadConstants.RESPONSE_OVERHEAD;
		int fits = budget / KadConstants.PEER_ENTRY_BASE_SIZE;

		return Math.max(1, Math.min(Math.min(wanted, KadConstants.MAX_PEERS_PER_RESPONSE), fits));
	}

	/**
	 * Estimated wire cost, in bytes, of one peer entry in a response.
	 * <p>
	 * The variable parts are measured from the entry itself and only the CBOR framing is estimated,
	 * which is as close to the encoded size as this can get without serializing the entry to find out.
	 * See {@link KadConstants#PEER_ENTRY_BASE_SIZE} for where the fixed part comes from.
	 * </p>
	 *
	 * @param peer the peer to size.
	 * @return the estimated number of bytes the entry costs in a response.
	 */
	static int peerEntrySize(PeerInfo peer) {
		int size = KadConstants.PEER_ENTRY_BASE_SIZE + peer.getEndpoint().getBytes(UTF_8).length;

		if (peer.isAuthenticated())
			size += KadConstants.PEER_ENTRY_NODE_AUTH_SIZE;

		byte[] extraData = peer.getExtraData();
		if (extraData != null)
			size += extraData.length;

		return size;
	}

	/**
	 * Takes the prefix of the given peers that fits in one datagram on this DHT's socket.
	 * <p>
	 * The count cap in {@link #peersPerResponse} cannot do this on its own, because a peer entry is
	 * variable-size: the endpoint is a free-form string and the extra data is free-form bytes. Both are
	 * bounded when a peer is announced, so a well-formed entry always fits - but a peer stored before
	 * those bounds existed may not, and one entry is enough to fragment a response.
	 * </p>
	 * <p>
	 * An entry that does not fit is <b>skipped</b> rather than ending the list, and no minimum is kept.
	 * A peer whose entry alone exceeds the budget cannot be delivered over this transport at all, so
	 * returning it would buy nothing and cost a fragmented datagram; the smaller peers behind it are
	 * still worth sending. An empty result falls through to the closest-node response, which is bounded
	 * separately.
	 * </p>
	 *
	 * @param peers the selected peers, in the order the storage returned them.
	 * @return the peers that fit, preserving order.
	 */
	List<PeerInfo> fitPeers(List<PeerInfo> peers) {
		if (peers.isEmpty())
			return peers;

		int budget = network.maxPacketSize() - KadConstants.RESPONSE_OVERHEAD;
		List<PeerInfo> fitted = new ArrayList<>(peers.size());

		for (PeerInfo peer : peers) {
			int size = peerEntrySize(peer);
			if (size > budget) {
				log.debug("Peer {} needs {} bytes, more than the {} left in the response, skipped",
						peer.getId(), size, budget);
				continue;
			}

			fitted.add(peer);
			budget -= size;
		}

		return fitted;
	}

	/**
	 * Whether a stored value can still be served in one datagram.
	 * <p>
	 * The value half of what {@link #fitPeers} does for peers, and it exists for the same reason: the
	 * limits are enforced when a value is stored, so anything accepted since they existed fits by
	 * construction - but a record written before them does not, and unlike a peer a value has no list
	 * to be dropped from. Serving it would fragment every response for that id, permanently, on a key
	 * nothing else can repair.
	 * </p>
	 * <p>
	 * Only the length is checked, not {@link Value#isValid()}: this decides what the transport can
	 * carry, and verifying a signature on every lookup would put elliptic-curve work on the event loop
	 * to answer a question nobody asked. A value that does not fit falls through to the closest-node
	 * response, which is bounded separately - the same answer this node would give if it held nothing.
	 * </p>
	 *
	 * @param value the stored value.
	 * @return {@code true} if the value is within the limit for its type.
	 */
	static boolean valueFits(Value value) {
		if (value.dataSize() <= value.maxDataBytes())
			return true;

		log.debug("Value {} holds {} bytes, more than the {} its type allows, not served",
				value.getId(), value.dataSize(), value.maxDataBytes());
		return false;
	}

	private void onFindNode(Message request) {
		FindNodeRequest body = request.getBody();
		Id target = body.getTarget();
		int want = nodesPerFamily(body.doesWant4(), body.doesWant6());
		int want4 = body.doesWant4() ? want : 0;
		int want6 = body.doesWant6() ? want : 0;

		populateClosestNodes(target, want4, want6).onSuccess(closest -> {
			int token = body.doesWantToken() ? tokenManager.generateToken(request.getId(),
					request.getRemoteIpAddress(), request.getRemotePort(), target) : 0;

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
			if (value != null && valueFits(value) && (!value.isMutable() || expectedSequenceNumber < 0 ||
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

	/**
	 * Verifies the anti-spoofing token on a write request, answering with an error and charging the sender
	 * if it does not hold.
	 *
	 * @param request the request carrying the token.
	 * @param token   the token presented.
	 * @param target  the id the token should have been issued for.
	 * @param method  the method name, for the log line and the error text.
	 * @return true if the caller should go on to process the request.
	 */
	private boolean verifyToken(Message request, int token, Id target, String method) {
		if (tokenManager.verifyToken(token, request.getId(), request.getRemoteIpAddress(),
				request.getRemotePort(), target))
			return true;

		log.debug("Received a {} request with invalid token from {}", method, request.getRemoteAddress());

		// A token names the id, address and port it was issued to, so one that does not verify says the
		// sender is not the party it was issued to - which is the whole point of the token, and the only
		// thing that makes a wrong one worth counting. Charging it is what puts a second bound on guessing
		// the token: the throttle limits how fast a source may guess, this limits how long it may keep
		// guessing before the source is refused outright.
		//
		// Unproven, and it has to be: an unsolicited request's source address is whatever the sender wrote,
		// so a spoofer could aim a stream of bad tokens at any address it likes. The unproven tier can
		// suppress that source briefly and can never ban it, which is the difference between raising an
		// attacker's cost and handing them a way to have someone else banned.
		suspiciousNodeDetector.inconsistent(request.getRemoteAddress(), request.getId());

		Message error = exceptionToError(request.getMethod(), request.getTxid(),
				new InvalidTokenException("Invalid token for " + method + " request"));
		error.setRemote(request.getId(), request.getRemoteAddress());
		sendResponse(error);
		return false;
	}

	private void onStoreValue(Message request) {
		StoreValueRequest body = request.getBody();
		Value value = body.getValue();

		// Checked here rather than inside the blocking section below. Two hashes decide whether a signature
		// verification and a worker-pool dispatch happen at all, so the cheap test has to come first to be
		// worth anything against a sender guessing tokens; and the detector it reports to is single-threaded
		// state owned by this event loop, which a worker thread must not touch.
		if (!verifyToken(request, body.getToken(), value.getId(), "STORE VALUE"))
			return;

		kadContext.executeBlocking(() -> {
			if (!value.isValid())
				throw new InvalidValueException("Invalid value for STORE VALUE request");

			return value;
		}, false).compose(validated ->
			// Atomic validate-and-store: existence check + immutable/CAS/owner validation + write in one
			// transaction (see DataStorage#putValue). failIfNotOwner=false: keep our own value on conflict.
			storage.putValue(validated, body.getExpectedSequenceNumber(), false, false)
		).transform(ar -> {
			Message response = ar.succeeded() ? Message.storeValueResponse(request.getTxid()) :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return sendResponse(response);
		});
	}

	private void onFindPeer(Message request) {
		FindPeerRequest body = request.getBody();
		Id target = body.getTarget();
		int expectedSequenceNumber = body.getExpectedSequenceNumber();
		int expectedCount = peersPerResponse(body.getExpectedCount());
		storage.getPeers(target, expectedSequenceNumber, expectedCount).map(this::fitPeers).compose(peers -> {
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

		PeerInfo peer = body.getPeer();

		// On the event loop, ahead of the signature check and the dispatch it gates - see onStoreValue.
		if (!verifyToken(request, body.getToken(), peer.getId(), "ANNOUNCE PEER"))
			return;

		kadContext.executeBlocking(() -> {
			if (!peer.isValid())
				throw new InvalidPeerException("Invalid value for ANNOUNCE PEER request");

			return peer;
		}, false).compose(validated ->
			// Atomic validate-and-store (see DataStorage#putPeer). failIfNotOwner=false: keep our own peer on conflict.
			storage.putPeer(validated, body.getExpectedSequenceNumber(), false, false)
		).transform(ar -> {
			Message response = ar.succeeded() ? Message.announcePeerResponse(request.getTxid()) :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return sendResponse(response);
		});
	}

	private void onUnknownMethod(Message request) {
		Message response = Message.error(request.getMethod(), request.getTxid(), ErrorCode.MethodUnknown.value(),
				"Unknown method: " + request.getMethod());
		response.setRemote(request.getId(), request.getRemoteAddress());
		sendResponse(response);
	}

	@SuppressWarnings("unused")
	private void onResponse(Message response) {
		// Nothing to do
	}

	/**
	 * How much of a peer-supplied string is worth putting in a log record.
	 */
	private static final int MAX_LOGGED_REMOTE_TEXT = 128;

	/**
	 * Renders a string that arrived over the wire as a single-line, length-bounded log field.
	 * <p>
	 * Two things a sender must not be able to do with text that ends up in our log: make one record
	 * arbitrarily long, and make one record look like several. A newline in an error message is enough
	 * for the second, so control characters are replaced rather than escaped - the point is that
	 * whatever comes back is one line of printable characters, not that it round-trips.
	 * </p>
	 *
	 * @param text the peer-supplied text, may be null.
	 * @return a bounded single-line rendering of the text.
	 */
	private static String forLog(@Nullable String text) {
		if (text == null)
			return "";

		int length = Math.min(text.length(), MAX_LOGGED_REMOTE_TEXT);
		StringBuilder repr = new StringBuilder(length + 3);
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			repr.append(c < 0x20 || c == 0x7f ? '.' : c);
		}

		if (text.length() > length)
			repr.append("...");

		return repr.toString();
	}

	private void onError(Message error) {
		Error body = error.getBody();
		// Every peer-supplied field on this line goes through forLog, including the version, which
		// Version.toString has already made printable. The redundancy is deliberate and the rule is
		// what makes it worth keeping: one that says "wrap what the sender wrote" survives the next
		// edit to this line, where one that says "wrap what is not sanitized elsewhere" needs whoever
		// makes that edit to know which fields those are. On an eight-character string the wrapper
		// costs nothing.
		//
		// It is not a substitute for the check in Version.toString, and neither is a substitute for the
		// other: the same version string reaches the log through KBucketEntry.toString and
		// Message.toString, where there is no wrapper and no call site to put one at.
		log.warn("Error from {}/{} - {}:{}, method {}, txid {}", error.getRemoteAddress(),
				forLog(error.getReadableVersion()), body.getCode(), forLog(body.getMessage()),
				error.getMethod(), error.getTxid());
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

	/**
	 * An endpoint presented a different id than the last one seen there: retire the binding it invalidates,
	 * as far as the evidence allows.
	 * <p>
	 * The routing table is worth having because it holds contacts that have been reachable a long time and
	 * are therefore likely to stay - that is the property lookups depend on. A binding that changes has
	 * failed that test, so it must stop being treated as good. Whether something at that address answers a
	 * ping is a different question and not the one being asked: a contact that churns is a poor contact even
	 * while it is up.
	 * </p>
	 * <p>
	 * <b>How far it is retired depends on what the report is worth</b>, and the two are not close:
	 * </p>
	 * <ul>
	 *   <li><b>Proven</b> - a response matched a call we had outstanding and came back from the address we
	 *       sent it to, carrying another id. The contact itself churned, nobody else could have aimed the
	 *       report, and the entry is removed.</li>
	 *   <li><b>Observed</b> - any other message. It decrypted, so it authenticates the id that sent it, but a
	 *       UDP source address is written by its sender. Removing on this would hand a spoofer an eviction
	 *       aimed at any endpoint it can name - and nothing bounds that, since the inbound throttle counts
	 *       against the address on the packet, which in that attack is the victim's, while the endpoints to
	 *       aim at are exactly what we hand out when asked who is nearby. So the entry is demoted instead.</li>
	 * </ul>
	 * <p>
	 * Demotion is the instrument the same uncertainty already earned in {@code received}, where a contact
	 * turning up at a new address is demoted rather than adopted or evicted. The two are mirror images - same
	 * id at the wrong address there, same address under a different id here - and in both the source address
	 * is the part carrying no proof. It stops the stale binding being handed to other nodes, wakes the
	 * {@code needsReplacement()} clause so two failures retire it rather than six, and leaves an attacker
	 * holding something the peer's next answer undoes rather than an eviction that resets its age and
	 * history. {@link RoutingTable#markUnreachable} is also its own latch: an entry already unreachable
	 * returns false, so repeating the report buys nothing further.
	 * </p>
	 * <p>
	 * A genuine id change at a fixed address therefore resolves in a minute or two when only observed, since
	 * the new binding waits for the demoted entry to retire. Nothing depends on that minute.
	 * </p>
	 * <p>
	 * The entry is only touched if it is still the one that churned. The report is keyed on an endpoint while
	 * the table is keyed on an id, so an id that has since moved elsewhere must not be retired for whatever
	 * now occupies its old address.
	 * </p>
	 *
	 * <p>
	 * No {@code isRunning()} guard, unlike the other RPC callbacks: the only caller is the receive path,
	 * which cannot produce a message without an open socket. Leaving it out keeps this a pure function of
	 * the routing table, which is what makes it testable without a deployment.
	 * </p>
	 *
	 * @param stale the binding that is no longer there - the id that used to be at that address.
	 * @param proven whether a response to one of our calls proved the change, as opposed to a message merely
	 *        showing it.
	 */
	void onChurn(NodeInfo stale, boolean proven) {
		KBucketEntry entry = routingTable.getEntry(stale.getId(), true);
		if (entry == null || !entry.getAddress().equals(stale.getAddress()))
			return;

		if (proven) {
			log.warn("Node {} at {} presented a different id, removing the stale routing table entry",
					stale.getId(), stale.getAddress());
			routingTable.remove(stale.getId());
		} else if (routingTable.markUnreachable(stale.getId())) {
			log.warn("Endpoint {} presented a different id, demoted the entry held for {}",
					stale.getAddress(), stale.getId());
		}

		// Nothing is reported to the suspicious-node detector from here. The Sybil budget for an endpoint
		// that rotates identities is charged where the change is seen, in the detector's own observed(),
		// because it is a fact about the endpoint rather than about this table - the churning identity need
		// not be a contact we hold, and where it is, the marker below stops the next one being learned, so a
		// table-gated budget would count once and then go quiet. SybilTests.TestIds proves that: moving the
		// charge here fails it outright.

		// Tell received() to skip the message that reported this, if one reaches it. See lastChurnedAddress.
		lastChurnedAddress = endpointKey(stale.getIpAddress(), stale.getPort());
	}

	/**
	 * Builds the {@link #lastChurnedAddress} for an endpoint.
	 * <p>
	 * Both sides derive it from an {@link InetAddress} rather than from a host string, so a literal that
	 * could be spelled more than one way - an IPv6 address above all - cannot produce two keys for one
	 * endpoint and leave a marker stranded.
	 * </p>
	 *
	 * @param address the endpoint's address.
	 * @param port the endpoint's port.
	 * @return the key.
	 */
	private static String endpointKey(InetAddress address, int port) {
		return address.getHostAddress() + ':' + port;
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

	void received(Message message) {
		InetAddress remoteAddress = message.getRemoteIpAddress();
		int remotePort = message.getRemotePort();

		// The identity at this endpoint changed, and onChurn has just retired the binding that held it - by
		// removal or by demotion, depending on what proved it. Either way, do not learn the binding that
		// reported the change.
		//
		// That refusal is the whole defence here. The source of a request is whatever the sender wrote, so
		// anyone can produce a churn report against a live peer's address, and learning the reporting id
		// from the same packet would let it install itself there. Keeping the demoted entry does not prevent
		// that on its own: the collision check lives in KBucket.put and scans only the bucket the new id
		// lands in, and an attacker choosing its id chooses its bucket. On the removing path it is worse
		// still - the reporting id takes the slot just vacated and the real peer is refused on its return.
		// Refusing instead costs a genuine id change one message: it is learned when the node speaks again.
		//
		// Spent here, at the top, rather than beside the put it guards: every return below this point is also
		// a decision not to update the table, so reading it late would leave it set on those paths. This
		// whole method is the routing-table update, so the earliest point is the correct one.
		//
		// Cleared whether or not it matches. A churn report is not always followed by a dispatch - the
		// wrong-id-in-a-response path in RpcServer answers the call and returns - so a marker that survived
		// a mismatch could outlive the exchange that set it and swallow an unrelated message later.
		//
		// The null check keeps the key off the hot path: this runs for every accepted message, and churn is
		// rare.
		if (lastChurnedAddress != null) {
			String armed = lastChurnedAddress;
			lastChurnedAddress = null;
			if (armed.equals(endpointKey(remoteAddress, remotePort)))
				return;
		}

		boolean allowed = kadContext.isDeveloperMode() ?
				AddressUtils.isAnyUnicast(remoteAddress) : AddressUtils.isGlobalUnicast(remoteAddress);
		if (!allowed) {
			log.debug("Received a message from unsupported address {}, ignored the potential routing table update",
					message.getRemoteAddress());
			return;
		}

		RpcCall call = message.getAssociatedCall();

		// A response that reaches this point is already consistent with the call it answers, so there is no
		// check to repeat here. Both halves of the old one moved to the RPC server, which is where the call
		// and the response are matched and so where the evidence actually exists:
		//
		// - a response whose id is not the one the request was addressed to is caught alongside the
		//   wrong-method check, and is *proven* churn - it matched our transaction id and came back from the
		//   address we sent to, so the address demonstrably receives our traffic;
		// - a response arriving from somewhere other than where the call was sent is caught by the
		//   inconsistent-socket branch, and is *unproven* - the source address is exactly what is in doubt.
		//
		// Keeping a copy here was not merely redundant, it was wrong in the second case: this method saw
		// both through one condition and reported both as proven, which is a classification an unverified
		// sender could have exploited. The address-mismatched half never actually arrived - the RPC server
		// answers that call and returns without dispatching - so the mistake stayed latent.

		// There used to be a force-removal here, driven by a per-address record of the last id seen there:
		// if the id at an address changed, both routing table entries were dropped and the whole bucket was
		// swept. It is gone, and the record backing it with it.
		//
		// The record was written by every message that arrived, including unsolicited requests, so its
		// contents were chosen by whoever sent them. That made the removal a primitive an attacker could
		// aim: two packets naming a live node's address, and its entry was evicted. Nothing rate-shaped it
		// either - no threshold, no decay, one packet per eviction - so unlike the suppression paths there
		// was no version of it that merely cost the victim time.
		//
		// A genuine id change at a fixed address still resolves, just more slowly: KBucket.put rejects the
		// new entry while the stale one holds the address, and admits it once that entry times out.
		Id id = message.getId();

		KBucketEntry existing = routingTable.getEntry(id, true);
		if (existing != null && (!existing.getIpAddress().equals(remoteAddress) ||
				existing.getPort() != remotePort)) {
			// this might happen if one node changes ports (broken NAT?) or IP address
			// ignore until routing table entry times out
			log.debug("Received a message from inconsistent node {}@{}, ignored the potential routing table update",
					message.getId(), message.getRemoteAddress());

			// The message authenticated as this id - it decrypted under that key - so the node itself is
			// saying it is somewhere other than where we have it. Two things follow, and they pull opposite
			// ways.
			//
			// The new address is still not adopted. A packet that authenticates as a node can be *relayed*
			// by anyone who captures it, so believing the source address would let one replayed packet move
			// any node's entry to an address the attacker chose, or to nowhere. Keeping the entry is what
			// makes that attack pointless.
			//
			// But we should not keep vouching for an address we have just been told is wrong either. So the
			// entry is demoted rather than adopted or evicted: it stays, and stops being treated as good.
			//
			// Demoting does three things, and only the first is obvious:
			//
			// 1. It stops the stale address spreading. eligibleForNodesList() requires reachability, so from
			//    this moment the address is no longer handed to every node that asks us who is nearby -
			//    which, until now, we kept doing for as long as the entry had fewer than three failures.
			//
			// 2. It makes the report happen once. The flag is also the latch: the next message from the new
			//    address finds the entry already unreachable and reports nothing. That matters because this
			//    report costs the *sender* a suppression hit, and a node that has genuinely moved keeps
			//    talking to us - without the latch, an ordinary address change would accumulate hits until
			//    the node was suppressed for having moved.
			//
			// 3. It brings the recovery forward by an order of magnitude. needsReplacement() asks for
			//    "failedRequests > 1 && !isReachable()", a clause that is dead for any node we ever verified,
			//    because a timeout only increments the counter and never clears the flag. Clearing it here
			//    revives that clause: two failures now retire the entry where six were needed before, or
			//    three plus fifteen minutes of silence. The node is re-learned at its new address in minutes
			//    instead of a quarter of an hour.
			//
			// The demotion is revocable by evidence, not a punishment: onResponded sets the flag back, so if
			// the old address is in fact still live - a relay, or a brief detour - the next successful
			// exchange undoes this. That is also the residual risk to be aware of: an on-path attacker who
			// captures one genuine packet can demote a healthy contact this way. It cannot evict it, and it
			// heals on the next answer, which is why demoting is the level chosen here.
			if (routingTable.markUnreachable(id)) {
				// Unproven source: reachable from an unsolicited request, whose source address is whatever
				// the sender wrote. Suppression only.
				suspiciousNodeDetector.inconsistent(message.getRemoteAddress(), message.getId());
			}

			return;
		}

		KBucketEntry newEntry = new KBucketEntry(id, new InetSocketAddress(remoteAddress, remotePort));
		newEntry.setVersion(message.getVersion());

		if (call != null) {
			newEntry.onResponded(call.getRTT());
			newEntry.updateLastSent(call.getSentTime());
		}

		boolean accepted = routingTable.put(newEntry);

		// Optimize: not the standard Kademlia behavior. A node we have not heard a response from gets one
		// unsolicited ping, to speed up the bootstrap process and to make the buckets more reliable.
		//
		// The gate is what the table did with the entry, not what state the bucket was in. Filing as a
		// replacement counts as accepted, and there is deliberately no bucket-fullness condition: put()
		// files an entry that is not yet reachable in the replacements, and this ping is what makes it
		// reachable, so gating on the bucket having room stops the promotion that would have made room.
		// Measured, not reasoned - restricting it to buckets with room empties the routing tables enough
		// that a lookup for a specific node id stops finding it.
		//
		// A rejected entry is not pinged because there would be nothing to ping for: the table kept a
		// conflicting entry in its place, so no answer to this ping could promote anything.
		//
		// That is a consistency gate rather than a budget, and it is not where the budget belongs. A
		// request's source address is unverified, so a sender forging addresses gets one ping emitted
		// towards each address it names; naming one address repeatedly collides with the entry the first
		// packet left behind and stops there, but the collision test covers the port, and a forged port
		// costs a sender nothing. The rate ceiling comes from the layer below, before anything arrives
		// here: the inbound throttle counts packets per source unit - one IPv4 address, one IPv6 /64 -
		// which neither a varying port nor a fresh address out of the same allocation escapes. The
		// suspicious-node detector adds to that only a short suppression, because a request's source is
		// exactly the thing it cannot verify.
		//
		// Rate is not the whole of it, though: what a rate bounds is how fast these arrive, not how many
		// are outstanding at once, and outstanding is what costs a slot in the active-call table that the
		// tasks are sized against. Marking the call as unsolicited is what puts it on its own sub-budget
		// there. A ping refused for want of budget needs nothing here - the entry stays in the table
		// unpromoted, and the periodic maintenance pings it in due course, which is what would have
		// happened without this optimization at all.
		if (accepted && existing == null && !newEntry.isReachable()) {
			Message request = Message.pingRequest();
			RpcCall ping = new RpcCall(newEntry, request).setUnsolicited(true);
			sendCallInternal(ping);
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

			if (!dispatchTask(task))
				promise.fail(new IllegalStateException("DHT is not running"));
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

			if (!dispatchTask(task))
				promise.fail(new IllegalStateException("DHT is not running"));
		});

		return promise.future();
	}

	/**
	 * Settles the caller's future from what the announce task actually achieved.
	 * <p>
	 * Every route out of an announce arrives here, including cancellation - the task is nested under the
	 * lookup, so a lookup that is cancelled or that finds nowhere to write cancels the announce, and this
	 * listener is the only thing that will ever settle the promise. Cancellation therefore has to be a
	 * failure rather than a silent completion, which is what it used to be.
	 * </p>
	 *
	 * @param promise the caller's promise.
	 * @param task    the finished announce task.
	 * @param what    the leading half of the failure message, naming the payload.
	 */
	private void completeAnnounce(Promise<AnnounceResult> promise, AnnounceTask<?> task, String what) {
		AnnounceResult result = task.getResult();
		if (!result.isFailure()) {
			// Everything except "asked and refused everywhere" completes, with the detail attached. One
			// node refusing is one node's claim, and letting it decide the outcome of the whole publish
			// would be a veto worth more to an attacker than to anyone else. Finding nobody to ask is
			// not a refusal at all - it is the ordinary state of a node that has not finished
			// bootstrapping, and the payload is offered again at the next cycle.
			promise.complete(result);
			return;
		}

		promise.fail(new AnnounceFailedException(what + ": " + result, result));
	}

	public Future<AnnounceResult> storeValue(Value value, int expectedSequenceNumber) {
		Promise<AnnounceResult> promise = Promise.promise();

		runOnContext(v -> {
			ValueAnnounceTask announceTask = new ValueAnnounceTask(kadContext, value, expectedSequenceNumber)
					.setName("Store value: " + value.getId())
					.addListener(t -> completeAnnounce(promise, t, "Value " + value.getId() + " was not stored"));

			NodeLookupTask lookupTask = new NodeLookupTask(kadContext, value.getId())
					.setWantToken(true)
					.setName("Store value: lookup closest node to - " + value.getId())
					.setNestedTask(announceTask)
					.addListener(t -> {
						if (t.getState() != Task.State.COMPLETED)
							return;

						ClosestSet closest = t.getClosestSet();
						if (closest == null || closest.isEmpty()) {
							// Routine, not an invariant violation: a node with a sparse or unreachable
							// routing table finds nothing to write to, and it used to be told the store
							// had succeeded. Cancelling reaches the listener above, which fails the
							// caller.
							log.debug("Value {} has nowhere to be stored: the lookup found no reachable nodes",
									value.getId());
							announceTask.cancel();
							return;
						}

						announceTask.closest(closest);
						// Cancelled rather than dropped, as in the branch above: the promise is completed
						// by this task's listener, so a task that never reaches a terminal state leaves
						// the caller waiting for a store that is not going to happen.
						if (!dispatchTask(announceTask))
							announceTask.cancel();
					});

			if (!dispatchTask(lookupTask))
				promise.fail(new IllegalStateException("DHT is not running"));
		});

		return promise.future();
	}

	@SuppressWarnings("unused")
	public Future<List<PeerInfo>> findPeer(Id id, int expectedSequenceNumber, int expectedCount, LookupOption option) {
		Promise<List<PeerInfo>> promise = Promise.promise();

		// Resolved here rather than taken as given: this entry point is public and, unlike KadNode's, had
		// no guard, so a caller asking for zero peers got a lookup that ended on its first response and
		// then discarded everything it had found. Zero means unspecified, the same as it does on the
		// receive side in peersPerResponse.
		final int peers = EligiblePeers.resolveExpectedCount(expectedCount);

		runOnContext(v -> {
			PeerLookupTask task = new PeerLookupTask(kadContext, id, expectedSequenceNumber, peers,
					option != LookupOption.CONSERVATIVE)
					.setName("Lookup peer: " + id)
					.addListener(t -> promise.complete(t.getResult().getPeers()));

			if (!dispatchTask(task))
				promise.fail(new IllegalStateException("DHT is not running"));
		});

		return promise.future();
	}

	public Future<AnnounceResult> announcePeer(PeerInfo peer, int expectedSequenceNumber) {
		Promise<AnnounceResult> promise = Promise.promise();

		runOnContext(v -> {
			PeerAnnounceTask announceTask = new PeerAnnounceTask(kadContext, peer, expectedSequenceNumber)
					.setName("Announce peer: " + peer.getId())
					.addListener(t -> completeAnnounce(promise, t, "Peer " + peer.getId() + " was not announced"));

			NodeLookupTask lookupTask = new NodeLookupTask(kadContext, peer.getId())
					.setWantToken(true)
					.setName("Announce peer: lookup closest node to - " + peer.getId())
					.setNestedTask(announceTask)
					.addListener(t -> {
						if (t.getState() != Task.State.COMPLETED)
							return;

						ClosestSet closest = t.getClosestSet();
						if (closest == null || closest.isEmpty()) {
							// Routine - see storeValue.
							log.debug("Peer {} has nowhere to be announced: the lookup found no reachable nodes",
									peer.getId());
							announceTask.cancel();
							return;
						}

						announceTask.closest(closest);
						// Cancelled rather than dropped, for the reason given in storeValue.
						if (!dispatchTask(announceTask))
							announceTask.cancel();
					});

			if (!dispatchTask(lookupTask))
				promise.fail(new IllegalStateException("DHT is not running"));
		});

		return promise.future();
	}

	public Future<Void> dumpRoutingTable(PrintStream out) {
		Promise<Void> promise = Promise.promise();
		// Read once, and require it to be present: before deployment both sides of this comparison
		// are null, which would otherwise dump an undeployed routing table and report success
		// instead of failing with "Vert.x context is not available".
		Context ctx = vertxContext;
		if (ctx != null && Vertx.currentContext() == ctx) {
			routingTable.dump(out);
			promise.complete();
		} else {
			runOnContext(v -> {
				routingTable.dump(out);
				promise.complete();
			});
		}

		return promise.future();
	}
}
