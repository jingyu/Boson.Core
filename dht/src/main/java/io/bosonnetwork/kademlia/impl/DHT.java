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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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

	private final RoutingTable routingTable;
	private long lastMaintenance;
	private final Path persistFile;

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
		// that the server-fallback tier sits strictly below the bootstrap tier: at large k they would
		// collide and then invert, collapsing the self-bootstrap band and sending routine maintenance
		// to the shared bootstrap servers. See KadConstants for the arithmetic.
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

		final boolean needPingCachedRoutingTable;
		if (persistFile != null && Files.exists(persistFile) && Files.isRegularFile(persistFile)) {
			log.info("Loading routing table from {} ...", persistFile);
			routingTable.load(persistFile);
			needPingCachedRoutingTable = !routingTable.isEmpty();
		} else {
			needPingCachedRoutingTable = false;
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

			List<Future<Void>> connectFutures = new ArrayList<>(routingTable.size() + 1);

			if (needPingCachedRoutingTable) {
				routingTable.forEachBucket(bucket -> {
					Promise<Void> promise = Promise.promise();
					PingRefreshTask task = new PingRefreshTask(kadContext)
							.setName("Bootstrap: ping cached routingtable - " + bucket.prefix())
							.removeOnTimeout(true)
							.bucket(bucket)
							.addListener(t -> promise.complete());
					taskManager.add(task);
					connectFutures.add(promise.future());
				});
			}

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

		int entries = routingTable.getNumberOfEntries();
		if (entries < bootstrapThreshold || System.currentTimeMillis() - lastBootstrap > KadConstants.SELF_LOOKUP_INTERVAL)
			// Regularly search for our id to update the routing table. Below one bucket's worth of
			// contacts we may be unable to reach the network unaided, so fall back to the configured
			// bootstrap servers; above it, self-bootstrap from what we already know and leave those
			// shared servers alone.
			doBootstrap(entries < useBootstrapNodesThreshold ? bootstrapNodes : Collections.emptyList());
	}

	private void routingTableMaintenance() {
		long now = System.currentTimeMillis();
		if (now - lastMaintenance < KadConstants.ROUTING_TABLE_MAINTENANCE_INTERVAL)
			return;

		log.info("Routing table maintenance ...");
		lastMaintenance = now;

		routingTable.maintenance(bootstrapIds, bucket ->
				tryPingMaintenance(bucket, false, false, true,
						"RoutingTable maintenance: refreshing bucket - " + bucket.prefix())
		);
	}

	private void randomLookup(long unusedTimerId) {
		if (rpcServer.isReachable()) {
			log.info("Periodic: random lookup ...");
			NodeLookupTask task = new NodeLookupTask(kadContext, Id.random())
					.setName("Periodic: random node Lookup");
			taskManager.add(task);
		} else {
			log.info("Periodic: not performing random lookup, server is unreachable.");
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
			log.info("Periodic: random ping - skip due to server has pending calls.");
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

	private Future<Void> doBootstrap(Collection<NodeInfo> bootstrapNodes) {
		if (bootstrapping)
			return Future.failedFuture(new IllegalStateException("DHT is bootstrapping"));

		if (System.currentTimeMillis() - lastBootstrap < KadConstants.BOOTSTRAP_MIN_INTERVAL)
			return Future.succeededFuture();

		if (bootstrapNodes.isEmpty() && routingTable.getNumberOfEntries() == 0) {
			log.warn("No bootstrap nodes found, and the routingtable is empty, skipping bootstrap.");
			return Future.succeededFuture();
		}

		bootstrapping = true;
		log.info("DHT {}:{} bootstrapping...", network, identity.getId());

		Future<Collection<NodeInfo>> future;
		if (!bootstrapNodes.isEmpty()) {
			// do random lookup to make ourselves known to random parts of the keyspace
			List<Future<List<NodeInfo>>> futures = new ArrayList<>(bootstrapNodes.size());
			for (NodeInfo node : bootstrapNodes) {
				Promise<List<NodeInfo>> promise = Promise.promise();

				Message request = Message.findNodeRequest(Id.random(), network.isIPv4(), network.isIPv6());
				RpcCall call = new RpcCall(node, request).addListener(new RpcCallListener() {
					@Override
					public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
						if (state.isFinal()) {
							if (state == RpcCall.State.RESPONDED) {
								Message response = c.getResponse();
								promise.complete(response.<FindNodeResponse>getBody().getNodes(network));
							} else {
								promise.complete(Collections.emptyList());
							}
						}
					}
				});

				futures.add(promise.future());
				rpcServer.sendCall(call);
			}

			future = Future.all(futures).map(cf -> {
				Map<Id, NodeInfo> nodes = new HashMap<>();
				for (int i = 0; i < cf.size(); i++) {
					List<NodeInfo> l = cf.resultAt(i);
					for (NodeInfo node : l)
						nodes.put(node.getId(), node);
				}
				return nodes.values();
			});
		} else {
			future = Future.succeededFuture(Collections.emptyList());
		}

		return future.compose(nodes -> {
			// breadth-first lookup: fill more buckets
			return (nodes.isEmpty() && routingTable.getNumberOfEntries() == 0) ?
					Future.succeededFuture() : fillHomeBucket(nodes);
		}).compose(v -> {
			// depth-first lookup: fill each bucket
			// only if the routing table is more than 1 bucket
			return (routingTable.size() <= 1) ? Future.succeededFuture() : fillBuckets();
		}).andThen(ar -> {
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
		taskManager.add(task, true);

		return promise.future();
	}

	private Future<Void> fillBuckets() {
		List<Future<Void>> futures = new ArrayList<>(routingTable.size());

		routingTable.forEachBucket(bucket -> {
			if (bucket.isFull() && routingTable.getNumberOfEntries() >= bootstrapThreshold)
				return;

			Promise<Void> promise = Promise.promise();
			bucket.updateRefreshTime();
			NodeLookupTask task = new NodeLookupTask(kadContext, bucket.prefix().createRandomId())
					.setName("Bootstrap: filling Bucket - " + bucket.prefix())
					.addListener(t -> promise.complete());
			taskManager.add(task, true);

			futures.add(promise.future());
		});

		return futures.isEmpty() ? Future.succeededFuture() : Future.all(futures).mapEmpty();
	}

	private Future<Void> pingRoutingTable() {
		if (routingTable.isEmpty())
			return Future.succeededFuture();

		List<Future<Void>> futures = new ArrayList<>(routingTable.size());
		routingTable.forEachBucket(bucket -> {
			if (!bucket.isEmpty()) {
				Promise<Void> promise = Promise.promise();
				PingRefreshTask task = new PingRefreshTask(kadContext)
						.setName("Bootstrap: cached routing table ping bucket - " + bucket.prefix())
						.removeOnTimeout(true)
						.addListener(t -> promise.complete());
				taskManager.add(task);

				futures.add(promise.future());
			}
		});

		return futures.isEmpty() ? Future.succeededFuture() : Future.all(futures).mapEmpty();
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