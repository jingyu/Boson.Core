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

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.ConnectionStatus;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Result;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.ImmutableSubstitutionFail;
import io.bosonnetwork.kademlia.exceptions.InvalidPeer;
import io.bosonnetwork.kademlia.exceptions.InvalidToken;
import io.bosonnetwork.kademlia.exceptions.InvalidValue;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpected;
import io.bosonnetwork.kademlia.exceptions.SequenceNotMonotonic;
import io.bosonnetwork.kademlia.metrics.DHTMetrics;
import io.bosonnetwork.kademlia.protocol.AnnouncePeerRequest;
import io.bosonnetwork.kademlia.protocol.Error;
import io.bosonnetwork.kademlia.protocol.FindNodeRequest;
import io.bosonnetwork.kademlia.protocol.FindNodeResponse;
import io.bosonnetwork.kademlia.protocol.FindPeerRequest;
import io.bosonnetwork.kademlia.protocol.FindPeerResponse;
import io.bosonnetwork.kademlia.protocol.FindValueRequest;
import io.bosonnetwork.kademlia.protocol.FindValueResponse;
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
	public static final int BOOTSTRAP_MIN_INTERVAL = 4 * 60 * 1000;                // 4 minutes
	public static final int SELF_LOOKUP_INTERVAL = 30 * 60 * 1000;                // 30 minutes
	public static final int ROUTING_TABLE_PERSIST_INTERVAL = 10 * 60 * 1000;    // 10 minutes
	public static final int ROUTING_TABLE_MAINTENANCE_INTERVAL = 4 * 60 * 1000;    // 4 minutes
	public static final int RANDOM_LOOKUP_INTERVAL = 10 * 60 * 1000;            // 10 minutes
	public static final int RANDOM_PING_INTERVAL = 10 * 1000;                    // 10 seconds
	public static final int BOOTSTRAP_IF_LESS_THAN_X_ENTRIES = 30;
	public static final int USE_BOOTSTRAP_NODES_IF_LESS_THAN_X_ENTRIES = 8;

	private final Identity identity;

	private final Network network;
	private final String host;
	private final int port;

	private final NodeInfo nodeInfo;

	private final DataStorage storage;
	private final Blacklist blacklist;
	private final TokenManager tokenManager;

	private final boolean enableSuspiciousNodeTracking;
	private final boolean enableSpamThrottling;
	private final DHTMetrics metrics;
	private final boolean enableDeveloperMode;

	private KadContext kadContext;
	private RpcServer rpcServer;

	private DHT sibling;

	private volatile boolean running;
	private ConnectionStatus status;
	private ConnectionStatusListener connectionStatusListener;

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

	public DHT(Identity identity, Network network, String host, int port, Collection<NodeInfo> bootstrapNodes,
			   DataStorage storage, Path persistFile, TokenManager tokenManager, Blacklist blacklist,
			   boolean enableSuspiciousNodeDetector, boolean enableSpamThrottling, DHTMetrics metrics,
			   boolean enableDeveloperMode) {
		this.identity = identity;
		this.network = network;
		this.host = host;
		this.port = port;
		this.storage = storage;
		this.persistFile = persistFile;
		this.tokenManager = tokenManager;
		this.blacklist = blacklist;

		this.enableSuspiciousNodeTracking = enableSuspiciousNodeDetector;
		this.enableSpamThrottling = enableSpamThrottling;
		this.metrics = metrics;
		this.enableDeveloperMode = enableDeveloperMode;

		this.routingTable = new RoutingTable(identity.getId());

		this.status = ConnectionStatus.Disconnected;
		this.running = false;

		this.bootstrapping = false;
		this.lastBootstrap = 0;

		this.timers = new ArrayList<>(6);

		this.bootstrapNodes = List.of();
		this.bootstrapIds = List.of();

		// Initialize suspicious node tracker
		this.suspiciousNodeDetector = enableSuspiciousNodeDetector ?
				SuspiciousNodeDetector.create() : SuspiciousNodeDetector.disabled();

		if (bootstrapNodes != null && !bootstrapNodes.isEmpty())
			addBootstrapNodes(bootstrapNodes);

		// TODO: improve
		this.nodeInfo = new NodeInfo(identity.getId(), host, port);
	}

	public boolean isRunning() {
		return running;
	}

	public Network getNetwork() {
		return network;
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

	protected void setSibling(DHT dht) {
		assert (dht != null && dht != this);
		this.sibling = dht;
	}

	public void setConnectionStatusListener(ConnectionStatusListener listener) {
		this.connectionStatusListener = listener;
	}

	@Override
	public void prepare(Vertx vertx, Context context) {
		super.prepare(vertx, context);
		this.kadContext = new KadContext(vertx, context, identity, network, this, enableDeveloperMode);
	}

	@Override
	public Future<Void> deploy() {
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
	public Future<Void> undeploy() {
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
		long timer = kadContext.setPeriodic(30 * 1000, 30 * 1000, this::update);
		timers.add(timer);

		// deep lookup to make ourselves known to random parts of the keyspace
		timer = kadContext.setPeriodic(RANDOM_LOOKUP_INTERVAL, RANDOM_LOOKUP_INTERVAL, this::randomLookup);
		timers.add(timer);

		// Do random node ping to check socket liveness
		timer = kadContext.setPeriodic(RANDOM_PING_INTERVAL, RANDOM_PING_INTERVAL, this::randomPing);
		timers.add(timer);

		if (enableSuspiciousNodeTracking) {
			timer = kadContext.setPeriodic(60 * 1000, 30 * 1000, unused -> suspiciousNodeDetector.purge());
			timers.add(timer);
		}

		if (persistFile != null) {
			timer = kadContext.setPeriodic(120_000, ROUTING_TABLE_PERSIST_INTERVAL, this::persistRoutingTable);
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
		if (entries < BOOTSTRAP_IF_LESS_THAN_X_ENTRIES || System.currentTimeMillis() - lastBootstrap > SELF_LOOKUP_INTERVAL)
			// Regularly search for our id to update the routing table
			doBootstrap(entries < USE_BOOTSTRAP_NODES_IF_LESS_THAN_X_ENTRIES ? bootstrapNodes : Collections.emptyList());

	}

	private void routingTableMaintenance() {
		long now = System.currentTimeMillis();
		if (now - lastMaintenance < ROUTING_TABLE_MAINTENANCE_INTERVAL)
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
				Message<Void> request = Message.pingRequest();
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
			addBootstrapNodes(nodes);
			if (bootstrapping) {
				promise.fail(new IllegalStateException("DHT is bootstrapping"));
				return;
			}

			lastBootstrap = 0; // force to bootstrap
			if (status == ConnectionStatus.Disconnected)
				setStatus(ConnectionStatus.Connecting);

			doBootstrap(nodes).onComplete(promise);
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

		if (System.currentTimeMillis() - lastBootstrap < BOOTSTRAP_MIN_INTERVAL)
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

				Message<FindNodeRequest> request = Message.findNodeRequest(Id.random(), network.isIPv4(), network.isIPv6());
				RpcCall call = new RpcCall(node, request).addListener(new RpcCallListener() {
					@Override
					public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
						if (state.isFinal()) {
							if (state == RpcCall.State.RESPONDED) {
								Message<FindNodeResponse> response = c.getResponse();
								promise.complete(response.getBody().getNodes(network));
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

	private void addBootstrapNodes(Collection<NodeInfo> nodes) {
		if (nodes.isEmpty())
			return;

		Map<Id, NodeInfo> dedup = new HashMap<>(this.bootstrapNodes.size() + nodes.size());
		bootstrapNodes.forEach(node -> dedup.put(node.getId(), node));

		// List<NodeInfo> newNodes = new ArrayList<>(nodes.size());
		for (NodeInfo node : nodes) {
			if (!network.canUseAddress(node.getIpAddress()))
				continue;

			if (node.getId().equals(identity.getId())) {
				log.warn("Can not bootstrap from local node");
				continue;
			}

			dedup.put(node.getId(), node);
			// NodeInfo old = dedup.put(node.getId(), node);
			// if (old == null || !old.equals(node))
			//	newNodes.add(node);
		}

		bootstrapNodes = List.copyOf(dedup.values());
		bootstrapIds = List.copyOf(dedup.keySet());
		return;
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
			if (bucket.isFull() && routingTable.getNumberOfEntries() >= BOOTSTRAP_IF_LESS_THAN_X_ENTRIES)
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
		if (rpcServer.isReachable())
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

		connectionStatusListener.statusChanged(network, status, old);
		switch (status) {
			case Connecting -> connectionStatusListener.connecting(network);
			case Connected -> connectionStatusListener.connected(network);
			case Disconnected -> connectionStatusListener.disconnected(network);
			default -> {}
		}
	}

	@SuppressWarnings("unchecked")
	private void onMessage(Message<?> message) {
		if (!isRunning())
			return;

		// ignore the messages from myself
		if (message.getId().equals(identity.getId()))
			return;

		switch (message.getType()) {
			case REQUEST -> onRequest(message);
			case RESPONSE -> onResponse(message);
			case ERROR -> onError((Message<Error>) message);
		}

		received(message);
	}

	@SuppressWarnings("unchecked")
	private void onRequest(Message<?> message) {
		switch (message.getMethod()) {
			case PING -> onPing((Message<Void>) message);
			case FIND_NODE -> onFindNode((Message<FindNodeRequest>) message);
			case FIND_VALUE -> onFindValue((Message<FindValueRequest>) message);
			case STORE_VALUE -> onStoreValue((Message<StoreValueRequest>) message);
			case FIND_PEER -> onFindPeer((Message<FindPeerRequest>) message);
			case ANNOUNCE_PEER -> onAnnouncePeer((Message<AnnouncePeerRequest>) message);
			default -> onUnknownMethod(message);
		}
	}

	private void onPing(Message<Void> request) {
		Message<Void> response = Message.pingResponse(request.getTxid())
				.setRemote(request.getRemoteId(), request.getRemoteAddress());
		rpcServer.sendMessage(response);
	}

	private void onFindNode(Message<FindNodeRequest> request) {
		Id target = request.getBody().getTarget();
		int want4 = request.getBody().doesWant4() ? KBucket.MAX_ENTRIES : 0;
		int want6 = request.getBody().doesWant4() ? KBucket.MAX_ENTRIES : 0;
		Result<List<? extends NodeInfo>> closest = populateClosestNodes(target, want4, want6);

		int token = request.getBody().doesWantToken() ?
				tokenManager.generateToken(request.getId(), request.getRemoteAddress(), target) : 0;

		Message<FindNodeResponse> response = Message.findNodeResponse(request.getTxid(), closest.getV4(), closest.getV6(), token)
				.setRemote(request.getId(), request.getRemoteAddress());
		rpcServer.sendMessage(response);
	}

	private void onFindValue(Message<FindValueRequest> request) {
		Id target = request.getBody().getTarget();
		int expectedSequenceNumber = request.getBody().getExpectedSequenceNumber();
		storage.getValue(target).map(value -> {
			Message<FindValueResponse> response;

			if (value != null && (!value.isMutable() || expectedSequenceNumber < 0 ||
					value.getSequenceNumber() >= expectedSequenceNumber)) {
				response = Message.findValueResponse(request.getTxid(), value);
			} else {
				int want4 = request.getBody().doesWant4() ? KBucket.MAX_ENTRIES : 0;
				int want6 = request.getBody().doesWant4() ? KBucket.MAX_ENTRIES : 0;
				Result<List<? extends NodeInfo>> closest = populateClosestNodes(target, want4, want6);
				response = Message.findValueResponse(request.getTxid(), closest.getV4(), closest.getV6());
			}

			return response;
		}).transform(ar -> {
			Message<?> response = ar.succeeded() ? ar.result() :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return rpcServer.sendMessage(response);
		});
	}

	private void onStoreValue(Message<StoreValueRequest> request) {
		kadContext.executeBlocking(() -> {
			Value value = request.getBody().getValue();

			if (!tokenManager.verifyToken(request.getBody().getToken(), request.getId(),
					request.getRemoteAddress(), value.getId())) {
				log.warn("Received a store value request with invalid token from {}", request.getRemoteAddress());
				throw new InvalidToken("Invalid token for STORE VALUE request");
			}

			if (!value.isValid())
				throw new InvalidValue("Invalid value for STORE VALUE request");

			return value;
		}).compose(value -> {
			return storage.getValue(value.getId()).compose(existing -> {
				if (existing != null) {
					// Immutable check
					if (existing.isMutable() != value.isMutable()) {
						log.warn("Rejecting value {}: cannot replace mismatched mutable/immutable", value.getId());
						return Future.failedFuture(new ImmutableSubstitutionFail("Cannot replace mismatched mutable/immutable value"));
					}

					if (value.getSequenceNumber() < existing.getSequenceNumber()) {
						log.warn("Rejecting value {}: sequence number not monotonic", value.getId());
						return Future.failedFuture(new SequenceNotMonotonic("Sequence number less than current"));
					}

					int expectedSequenceNumber = request.getBody().getExpectedSequenceNumber();
					if (expectedSequenceNumber >= 0 && existing.getSequenceNumber() > expectedSequenceNumber) {
						log.warn("Rejecting value {}: sequence number not expected", value.getId());
						return Future.failedFuture(new SequenceNotExpected("Sequence number not expected"));
					}

					if (existing.hasPrivateKey() && !value.hasPrivateKey()) {
						// Skip update if the existing value is owned by this node and the new value is not.
						// Should not throw NotOwnerException, just silently ignore to avoid disrupting valid operations.
						log.info("Skipping to update value for id {}: owned by this node", value.getId());
						return Future.succeededFuture(existing);
					}
				}

				return storage.putValue(value);
			});
		}).transform(ar -> {
			Message<?> response = ar.succeeded() ? Message.storeValueResponse(request.getTxid()) :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return rpcServer.sendMessage(response);
		});
	}

	private void onFindPeer(Message<FindPeerRequest> request) {
		Id target = request.getBody().getTarget();
		int expectedSequenceNumber = request.getBody().getExpectedSequenceNumber();
		int expectedCount = request.getBody().getExpectedCount() > 0 ? request.getBody().getExpectedCount() : 16;
		storage.getPeers(target, expectedSequenceNumber, expectedCount).map(peers -> {
			Message<FindPeerResponse> response;

			if (!peers.isEmpty()) {
				response = Message.findPeerResponse(request.getTxid(), peers);
			} else {
				int want4 = request.getBody().doesWant4() ? KBucket.MAX_ENTRIES : 0;
				int want6 = request.getBody().doesWant4() ? KBucket.MAX_ENTRIES : 0;
				Result<List<? extends NodeInfo>> closest = populateClosestNodes(target, want4, want6);
				response = Message.findPeerResponse(request.getTxid(), closest.getV4(), closest.getV6());
			}

			return response;
		}).transform(ar -> {
			Message<?> response = ar.succeeded() ? ar.result() :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return rpcServer.sendMessage(response);
		});
	}

	private void onAnnouncePeer(Message<AnnouncePeerRequest> request) {
		InetAddress remoteAddress = request.getRemoteIpAddress();
		boolean allowed = kadContext.isDeveloperMode() ?
				AddressUtils.isAnyUnicast(remoteAddress) : AddressUtils.isGlobalUnicast(remoteAddress);
		if (!allowed) {
			log.debug("Received an announce peer request from unsupported address {}, ignored",
					request.getRemoteAddress());
			return;
		}

		kadContext.executeBlocking(() -> {
			PeerInfo peer = request.getBody().getPeer();

			if (!tokenManager.verifyToken(request.getBody().getToken(), request.getId(),
					request.getRemoteAddress(), peer.getId())) {
				log.warn("Received a announce peer request with invalid token from {}", request.getRemoteAddress());
				throw new InvalidToken("Invalid token for ANNOUNCE PEER request");
			}

			if (!peer.isValid())
				throw new InvalidPeer("Invalid value for ANNOUNCE PEER request");

			return peer;
		}).compose(peer -> {
			return storage.getPeer(peer.getId(), peer.getFingerprint()).compose(existing -> {
				if (existing != null) {
					if (peer.getSequenceNumber() < existing.getSequenceNumber()) {
						log.warn("Rejecting peer {}: sequence number not monotonic", peer.getId());
						return Future.failedFuture(new SequenceNotMonotonic("Sequence number less than current"));
					}

					int expectedSequenceNumber = request.getBody().getExpectedSequenceNumber();
					if (expectedSequenceNumber >= 0 && existing.getSequenceNumber() > expectedSequenceNumber) {
						log.warn("Rejecting peer {}: sequence number not expected", peer.getId());
						return Future.failedFuture(new SequenceNotExpected("Sequence number not expected"));
					}

					if (existing.hasPrivateKey() && !peer.hasPrivateKey()) {
						// Skip update if the existing peer is owned by this node and the new peer is not.
						// Should not throw NotOwnerException, just silently ignore to avoid disrupting valid operations.
						log.info("Skipping to update peer for id {}: owned by this node", peer.getId());
						return Future.succeededFuture(existing);
					}
				}

				return storage.putPeer(peer);
			});
		}).transform(ar -> {
			Message<?> response = ar.succeeded() ? Message.announcePeerResponse(request.getTxid()) :
					exceptionToError(request.getMethod(), request.getTxid(), ar.cause());
			response.setRemote(request.getId(), request.getRemoteAddress());
			return rpcServer.sendMessage(response);
		});
	}

	private void onUnknownMethod(Message<?> request) {
		Message<?> response = Message.error(request.getMethod(), request.getTxid(), ErrorCode.MethodUnknown.value(),
				"Unknown method: " + request.getMethod());
		response.setRemote(request.getId(), request.getRemoteAddress());
		rpcServer.sendMessage(response);
	}

	@SuppressWarnings("unused")
	private void onResponse(Message<?> response) {
		// Nothing to do
	}

	private void onError(Message<Error> error) {
		log.warn("Error from {}/{} - {}:{}, method {}, txid {}", error.getRemoteAddress(), error.getReadableVersion(),
				error.getBody().getCode(), error.getBody().getMessage(), error.getMethod(), error.getTxid());
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

	private Message<Error> exceptionToError(Message.Method method, long txid, Throwable cause) {
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

	private void received(Message<?> message) {
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
			Message<Void> request = Message.pingRequest();
			RpcCall ping = new RpcCall(newEntry, request);
			rpcServer.sendCall(ping);
		}
	}

	private Result<List<? extends NodeInfo>> populateClosestNodes(Id target, int v4, int v6) {
		List<NodeInfo> nodes4 = List.of();
		List<NodeInfo> nodes6 = List.of();

		if (v4 > 0) {
			DHT dht4 = network == Network.IPv4 ? this : sibling;
			if (dht4 != null) {
				nodes4 = routingTable.getClosestNodes(target, v4)
						.includeReplacements(routingTable.getNumberOfEntries() < v4)
						.fill()
						.nodes();
				// Add self to the list if needed
				if (nodes4.size() < v4)
					nodes4.add(nodeInfo);
			}
		}

		if (v6 > 0) {
			DHT dht6 = network == Network.IPv6 ? this : sibling;
			if (dht6 != null) {
				nodes6 = routingTable.getClosestNodes(target, v6)
						.includeReplacements(routingTable.getNumberOfEntries() < v6)
						.fill()
						.nodes();
				// Add self to the list if needed
				if (nodes6.size() < v6)
					nodes6.add(nodeInfo);
			}
		}

		return new Result<>(nodes4, nodes6);
	}

	public Future<NodeInfo> findNode(Id id, LookupOption option) {
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