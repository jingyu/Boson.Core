/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.bosonnetwork.ConnectionStatus;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.protocol.deprecated.AnnouncePeerRequest;
import io.bosonnetwork.kademlia.protocol.deprecated.AnnouncePeerResponse;
import io.bosonnetwork.kademlia.protocol.deprecated.ErrorMessage;
import io.bosonnetwork.kademlia.protocol.deprecated.FindNodeRequest;
import io.bosonnetwork.kademlia.protocol.deprecated.FindNodeResponse;
import io.bosonnetwork.kademlia.protocol.deprecated.FindPeerRequest;
import io.bosonnetwork.kademlia.protocol.deprecated.FindPeerResponse;
import io.bosonnetwork.kademlia.protocol.deprecated.FindValueRequest;
import io.bosonnetwork.kademlia.protocol.deprecated.FindValueResponse;
import io.bosonnetwork.kademlia.protocol.deprecated.LookupResponse;
import io.bosonnetwork.kademlia.protocol.deprecated.OldMessage;
import io.bosonnetwork.kademlia.protocol.deprecated.PingRequest;
import io.bosonnetwork.kademlia.protocol.deprecated.PingResponse;
import io.bosonnetwork.kademlia.protocol.deprecated.StoreValueRequest;
import io.bosonnetwork.kademlia.protocol.deprecated.StoreValueResponse;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.routing.KClosestNodes;
import io.bosonnetwork.kademlia.routing.RoutingTable;
import io.bosonnetwork.kademlia.security.SuspiciousNodeTracker;
import io.bosonnetwork.kademlia.storage.deprecated.DataStorage;
import io.bosonnetwork.kademlia.tasks.ClosestSet;
import io.bosonnetwork.kademlia.tasks.NodeLookup;
import io.bosonnetwork.kademlia.tasks.PeerAnnounce;
import io.bosonnetwork.kademlia.tasks.PeerLookup;
import io.bosonnetwork.kademlia.tasks.PingRefreshTask;
import io.bosonnetwork.kademlia.tasks.Task;
import io.bosonnetwork.kademlia.tasks.TaskListener;
import io.bosonnetwork.kademlia.tasks.TaskManager;
import io.bosonnetwork.kademlia.tasks.ValueAnnounce;
import io.bosonnetwork.kademlia.tasks.ValueLookup;
import io.bosonnetwork.utils.AddressUtils;

/**
 * @hidden
 */
public class DHT {
	private final Network type;

	private final Node node;
	private final InetSocketAddress addr;
	private RPCServer server;

	private ConnectionStatus status;

	private boolean running;
	private final List<ScheduledFuture<?>> scheduledActions;

	private Path persistFile;

	private final Set<NodeInfo> bootstrapNodes;
	private final AtomicBoolean bootstrapping;
	private final BootstrapStage bootstrapStage;
	private long lastBootstrap;

	private final RoutingTable routingTable;
	private long lastSave;
	private final Cache<InetSocketAddress, Id> knownNodes;

	private final TaskManager taskMan;

	private final SuspiciousNodeTracker suspiciousNodeTracker;

	private static final Logger log = LoggerFactory.getLogger(DHT.class);

	static enum CompletionStatus {
		Pending,
		Canceled,
		Completed
	}

	class BootstrapStage {
		private CompletionStatus fillHomeBucket = CompletionStatus.Pending;
		private CompletionStatus fillAllBuckets = CompletionStatus.Pending;
		private CompletionStatus pingCachedRoutingTable = CompletionStatus.Pending;

		public void fillHomeBucket(CompletionStatus status) {
			fillHomeBucket = status;
			updateConnectionStatus();
		}

		public void fillAllBuckets(CompletionStatus status) {
			fillAllBuckets = status;
			updateConnectionStatus();
		}

		public void pingCachedRoutingTable(CompletionStatus status) {
			pingCachedRoutingTable = status;
			updateConnectionStatus();
		}

		public void clearBootstrapStatus() {
			fillHomeBucket = CompletionStatus.Pending;
			fillAllBuckets = CompletionStatus.Pending;
		}

		private boolean completed(CompletionStatus status) {
			return status.ordinal() > CompletionStatus.Pending.ordinal();
		}

		private synchronized void updateConnectionStatus() {
			log.debug("BootstrapStage {}: [{}, {}, {}]", getNode().getId(), fillHomeBucket, fillAllBuckets, pingCachedRoutingTable);

			if (completed(fillAllBuckets) && completed(pingCachedRoutingTable)) {
				if (routingTable.getNumBucketEntries() > 0)
					setStatus(ConnectionStatus.Connected, ConnectionStatus.Profound);

				return;
			}

			if (completed(fillHomeBucket) || completed(pingCachedRoutingTable)) {
				if (routingTable.getNumBucketEntries() > 0)
					setStatus(ConnectionStatus.Connecting, ConnectionStatus.Connected);

				return;
			}
		}
	}

	public DHT(Network type, Node node, InetSocketAddress addr) {
		this.type = type;
		this.node = node;
		this.addr = addr;
		this.scheduledActions = new ArrayList<>();
		this.routingTable = new RoutingTable(this);
		this.bootstrapNodes = ConcurrentHashMap.newKeySet();
		this.bootstrapping = new AtomicBoolean(false);

		this.status = ConnectionStatus.Disconnected;
		this.bootstrapStage = new BootstrapStage();

		this.knownNodes = Caffeine.newBuilder()
				.initialCapacity(256)
				.expireAfterAccess(Constants.KBUCKET_OLD_AND_STALE_TIME, TimeUnit.MILLISECONDS)
				.build();

		this.suspiciousNodeTracker = new SuspiciousNodeTracker();

		this.taskMan = new TaskManager(this);
	}

	public Network getType() {
		return type;
	}

	public InetSocketAddress getAddress() {
		return addr;
	}

	public Node getNode() {
		return node;
	}

	private void setStatus(ConnectionStatus expected, ConnectionStatus newStatus) {
		if (this.status.equals(expected)) {
			ConnectionStatus old = this.status;
			this.status = newStatus;

			List<ConnectionStatusListener> listeners = node.getConnectionStatusListeners();
			if (!listeners.isEmpty()) {
				for (ConnectionStatusListener l : listeners) {
					l.statusChanged(type, newStatus, old);

					switch (newStatus) {
					case Connected:
						l.connected(type);
						break;

					case Profound:
						l.profound(type);
						break;

					case Disconnected:
						l.disconnected(type);
						break;

					default:
						break;
					}
				}
			}
		} else {
			log.warn("Set connection status failed, expected is {}, actual is {}", expected, status);
		}
	}

	public NodeInfo getNode(Id nodeId) {
		NodeInfo ni = routingTable.getEntry(nodeId, true);
		if (ni == null && node.isLocalId(nodeId)) {
			ni = new NodeInfo(nodeId, getAddress());
			ni.setVersion(Constants.VERSION);
		}

		return ni;
	}

	public RoutingTable getRoutingTable() {
		return routingTable;
	}

	public TaskManager getTaskManager() {
		return taskMan;
	}

	void setRPCServer(RPCServer server) {
		this.server = server;
	}

	public RPCServer getServer() {
		return server;
	}

	void enablePersistence(Path persistFile) {
		this.persistFile = persistFile;
	}

	public Collection<NodeInfo> getBootstraps() {
		return Collections.unmodifiableSet(bootstrapNodes);
	}

	public Collection<Id> getBootstrapIds() {
		return bootstrapNodes.stream().map(NodeInfo::getId).collect(Collectors.toUnmodifiableSet());
	}

	SuspiciousNodeTracker getSuspiciousNodeTracker() {
		return suspiciousNodeTracker;
	}

	protected void bootstrap() {
		if (!isRunning() || System.currentTimeMillis() - lastBootstrap < Constants.BOOTSTRAP_MIN_INTERVAL)
			return;


		Set<NodeInfo> bns = !bootstrapNodes.isEmpty() ?
				bootstrapNodes : routingTable.getRandomEntries(8);
		if (bns.isEmpty())
			return;

		if (!bootstrapping.compareAndSet(false, true))
			return;

		bootstrapStage.clearBootstrapStatus();

		log.info("DHT {} bootstraping...", type);

		List<CompletableFuture<List<NodeInfo>>> futures = new ArrayList<>(bns.size());

		for (NodeInfo node : bns) {
			CompletableFuture<List<NodeInfo>> future = new CompletableFuture<>();

			FindNodeRequest request = new FindNodeRequest(Id.random());
			request.setWant4(type == Network.IPv4);
			request.setWant6(type == Network.IPv6);

			RPCCall call = new RPCCall(node, request).addListener(new RPCCallListener() {
				@Override
				public void onStateChange(RPCCall c, RPCCall.State previous, RPCCall.State current) {
					if (current == RPCCall.State.RESPONDED || current == RPCCall.State.ERROR
							|| current == RPCCall.State.TIMEOUT) {
						if (c.getResponse() instanceof FindNodeResponse r)
							future.complete(r.getNodes(getType()));
						else
							future.complete(Collections.emptyList());
					}
				}
			});

			futures.add(future);
			getServer().sendCall(call);
		}

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenAccept((x) -> {
			Set<NodeInfo> nodes = futures.stream().map(f -> {
				List<NodeInfo> l;
				try {
					l = f.get();
				} catch (Exception e) {
					l = Collections.emptyList();
				}
				return l;
			}).flatMap(Collection::stream).collect(Collectors.toSet());

			lastBootstrap = System.currentTimeMillis();
			fillHomeBucket(nodes);
		});
	}

	public void bootstrap(Collection<NodeInfo> bootstrapNodes) {
		int added = 0;

		for (NodeInfo bootstrapNode : bootstrapNodes) {
			if (!type.canUseAddress(bootstrapNode.getInetAddress()))
				continue;

			if (node.isLocalId(bootstrapNode.getId())) {
				log.warn("Can not bootstrap from local node: {}", node.getId());
				continue;
			}

			if (!this.bootstrapNodes.contains(bootstrapNode)) {
				this.bootstrapNodes.add(bootstrapNode);
				added++;
			}
		}

		if (added > 0) {
			lastBootstrap = 0;
			bootstrap();
		}
	}

	private void fillHomeBucket(Collection<NodeInfo> nodes) {
		if (routingTable.getNumBucketEntries() == 0 && nodes.isEmpty()) {
			bootstrapping.set(false);
			return;
		}

		TaskListener bootstrapListener = t -> {
			bootstrapping.set(false);

			if (!isRunning())
				return;

			bootstrapStage.fillHomeBucket(CompletionStatus.Completed);

			if (routingTable.getNumBucketEntries() > Constants.MAX_ENTRIES_PER_BUCKET + 2)
				routingTable.fillBuckets().thenAccept((v) -> {
					bootstrapStage.fillAllBuckets(CompletionStatus.Completed);
				});
			else
				bootstrapStage.fillAllBuckets(CompletionStatus.Canceled);
		};


		NodeLookup task = new NodeLookup(this, getNode().getId());
		task.setBootstrap(true);
		task.setName("Bootstrap: filling home bucket");
		task.injectCandidates(nodes);
		task.addListener(bootstrapListener);
		getTaskManager().add(task, true);
	}

	private void update() {
		if (!isRunning())
			return;

		// log.trace("DHT {} regularly update...", type);

		long now = System.currentTimeMillis();

		server.checkReachability(now);
		routingTable.maintenance();

		if (routingTable.getNumBucketEntries() < Constants.BOOTSTRAP_IF_LESS_THAN_X_PEERS ||
				now - lastBootstrap > Constants.SELF_LOOKUP_INTERVAL)
			// Regularly search for our id to update routing table
			bootstrap();

		if (persistFile != null && (now - lastSave) > Constants.ROUTING_TABLE_PERSIST_INTERVAL) {
			try {
				log.info("Persisting routing table ...");
				routingTable.save(persistFile);
				lastSave = now;
			} catch (IOException e) {
				log.error("Can not save the routing table: " + e.getMessage(), e);
			}
		}
	}

	public synchronized void start(Collection<NodeInfo> bootstrapNodes) throws KadException {
		if (running)
			return;

		if (persistFile != null && Files.exists(persistFile) && Files.isRegularFile(persistFile)) {
			log.info("Loading routing table from {} ...", persistFile);
			routingTable.load(persistFile);
		}

		Set<NodeInfo> bns = bootstrapNodes.stream().filter(
				n -> type.canUseAddress(n.getInetAddress()) && !node.getId().equals(n.getId()))
				.collect(Collectors.toSet());
		this.bootstrapNodes.addAll(bns);

		log.info("Starting DHT/{} on {}", type, AddressUtils.toString(addr));

		server = new RPCServer(this, addr);
		server.start();

		running = true;
		setStatus(ConnectionStatus.Disconnected, ConnectionStatus.Connecting);

		// tasks maintenance that should run all the time, before the first queries
		scheduledActions.add(getNode().getScheduler().scheduleWithFixedDelay(taskMan::dequeue, 5000,
				Constants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));

		// Ping check if the routing table loaded from cache
		if (routingTable.getNumBucketEntries() > 0)
			routingTable.pingBuckets().thenAccept((v) -> {
				bootstrapStage.pingCachedRoutingTable(CompletionStatus.Completed);
			});
		else
			bootstrapStage.pingCachedRoutingTable(CompletionStatus.Canceled);

		if (!this.bootstrapNodes.isEmpty()) {
			bootstrap();
		} else {
			bootstrapStage.fillHomeBucket(CompletionStatus.Canceled);
			bootstrapStage.fillAllBuckets(CompletionStatus.Canceled);
		}


		// fix the first time to persist the routing table: 2 min
		lastSave = System.currentTimeMillis() - Constants.ROUTING_TABLE_PERSIST_INTERVAL + (120 * 1000);

		// Regularly DHT update
		scheduledActions.add(getNode().getScheduler().scheduleWithFixedDelay(() -> {
			try {
				update();
			} catch (Exception e) {
				log.error("Regularly DHT update failed", e);
			}
		}, 5000, Constants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));

		// send a ping request to a random node to check socket liveness
		scheduledActions.add(getNode().getScheduler().scheduleWithFixedDelay(() -> {
			if (server.getNumberOfActiveRPCCalls() > 0)
				return;

			KBucketEntry entry = routingTable.getRandomEntry();
			if (entry == null)
				return;

			PingRequest q = new PingRequest();
			RPCCall c = new RPCCall(entry, q);
			server.sendCall(c);
		}, Constants.RANDOM_PING_INTERVAL, Constants.RANDOM_PING_INTERVAL, TimeUnit.MILLISECONDS));

		// deep lookup to make ourselves known to random parts of the keyspace
		scheduledActions.add(getNode().getScheduler().scheduleWithFixedDelay(() -> {
			NodeLookup task = new NodeLookup(this, Id.random());
			task.setName(type + ":Random Refresh Lookup");
			taskMan.add(task);
		}, Constants.RANDOM_LOOKUP_INTERVAL, Constants.RANDOM_LOOKUP_INTERVAL, TimeUnit.MILLISECONDS));
	}

	public void stop() {
		if (!running)
			return;

		log.info("{} initated DHT shutdown...", type);

		// Cancel the search tasks
		// Stream.concat(Arrays.stream(tman.getActiveTasks()),
		// Arrays.stream(tman.getQueuedTasks())).forEach(Task::kill);

		log.info("stopping servers");
		running = false;
		server.stop();

		// Cancel all scheduled actions
		for (ScheduledFuture<?> future : scheduledActions) {
			future.cancel(false);
			// none of the scheduled tasks should experience exceptions,
			// log them if they did
			try {
				future.get();
			} catch (ExecutionException | InterruptedException e) {
				log.error("Scheduled future error", e);
			} catch (CancellationException ignore) {
			}
		}

		scheduledActions.clear();

		if (persistFile != null) {
			try {
				log.info("Persisting routing table on shutdown...");
				routingTable.save(persistFile);
			} catch (IOException e) {
				log.error("Persisting routing table failed", e);
			}
		}

		taskMan.cancleAll();
	}

	public boolean isRunning() {
		return running;
	}

	protected void onMessage(OldMessage msg) {
		if (!isRunning())
			return;

		// ignore the messages we get from ourself
		if (node.isLocalId(msg.getId()))
			return;

		switch (msg.getType()) {
		case REQUEST:
			onRequest(msg);
			break;

		case RESPONSE:
			onResponse(msg);
			break;

		case ERROR:
			onError((ErrorMessage) msg);
			break;
		}

		received(msg);
	}

	private void onRequest(OldMessage msg) {
		switch (msg.getMethod()) {
		case PING:
			onPing((PingRequest) msg);
			break;

		case FIND_NODE:
			onFindNode((FindNodeRequest) msg);
			break;

		case FIND_VALUE:
			onFindValue((FindValueRequest) msg);
			break;

		case STORE_VALUE:
			onStoreValue((StoreValueRequest) msg);
			break;

		case FIND_PEER:
			onFindPeers((FindPeerRequest) msg);
			break;

		case ANNOUNCE_PEER:
			onAnnouncePeer((AnnouncePeerRequest) msg);
			break;

		case UNKNOWN:
			sendError(msg, ErrorCode.ProtocolError.value(), "Invalid request method");
			break;
		}
	}

	private void onPing(PingRequest q) {
		PingResponse r = new PingResponse(q.getTxid());
		r.setRemote(q.getId(), q.getOrigin());
		server.sendMessage(r);
	}

	private void onFindNode(FindNodeRequest q) {
		FindNodeResponse r = new FindNodeResponse(q.getTxid());

		int want4 = q.doesWant4() ? Constants.MAX_ENTRIES_PER_BUCKET : 0;
		int want6 = q.doesWant6() ? Constants.MAX_ENTRIES_PER_BUCKET : 0;
		populateClosestNodes(r, q.getTarget(), want4, want6);

		if (q.doesWantToken())
			r.setToken(getNode().getTokenManager().generateToken(q.getId(), q.getOrigin(), q.getTarget()));

		r.setRemote(q.getId(), q.getOrigin());
		server.sendMessage(r);
	}

	private void onFindValue(FindValueRequest q) {
		DataStorage storage = getNode().getStorage();

		Id target = q.getTarget();
		FindValueResponse r = new FindValueResponse(q.getTxid());
		r.setToken(getNode().getTokenManager().generateToken(q.getId(), q.getOrigin(), target));

		try {
			boolean hasValue = false;
			Value value = storage.getValue(target);
			if (value != null) {
				if (q.getSequenceNumber() < 0 || value.getSequenceNumber() < 0
						|| q.getSequenceNumber() <= value.getSequenceNumber()) {
					r.setValue(value);

					hasValue = true;
				}
			}

			if (!hasValue) {
				int want4 = q.doesWant4() ? Constants.MAX_ENTRIES_PER_BUCKET : 0;
				int want6 = q.doesWant6() ? Constants.MAX_ENTRIES_PER_BUCKET : 0;
				populateClosestNodes(r, target, want4, want6);
			}

			r.setRemote(q.getId(), q.getOrigin());
			server.sendMessage(r);
		} catch (KadException e) {
			ErrorMessage em = new ErrorMessage(q.getMethod(), q.getTxid(), e.getCode(), e.getMessage());
			em.setRemote(q.getId(), q.getOrigin());
			server.sendMessage(em);
		}
	}

	private void onStoreValue(StoreValueRequest q) {
		DataStorage storage = getNode().getStorage();

		Id valueId = q.getValueId();
		if (!getNode().getTokenManager().verifyToken(q.getToken(), q.getId(), q.getOrigin(), valueId)) {
			log.warn("Received a store value request with invalid token from {}", AddressUtils.toString(q.getOrigin()));
			sendError(q, ErrorCode.ProtocolError.value(), "Invalid token for STORE VALUE request");
			return;
		}

		Value v = q.getValue();
		if (!v.isValid()) {
			sendError(q, ErrorCode.ProtocolError.value(), "Invalue value");
			return;
		}

		try {
			storage.putValue(v, q.getExpectedSequenceNumber());
			StoreValueResponse r = new StoreValueResponse(q.getTxid());
			r.setRemote(q.getId(), q.getOrigin());
			server.sendMessage(r);
		} catch (KadException e) {
			sendError(q, e.getCode(), e.getMessage());
		}
	}

	private void onFindPeers(FindPeerRequest q) {
		DataStorage storage = getNode().getStorage();

		Id target = q.getTarget();
		FindPeerResponse r = new FindPeerResponse(q.getTxid());
		r.setToken(getNode().getTokenManager().generateToken(q.getId(), q.getOrigin(), target));

		try {
			boolean hasPeers = false;

			List<PeerInfo> peers = storage.getPeer(target, 8);
			if (!peers.isEmpty()) {
				r.setPeers(peers);
				hasPeers = true;
			}

			if (!hasPeers) {
				int want4 = q.doesWant4() ? Constants.MAX_ENTRIES_PER_BUCKET : 0;
				int want6 = q.doesWant6() ? Constants.MAX_ENTRIES_PER_BUCKET : 0;
				populateClosestNodes(r, q.getTarget(), want4, want6);
			}

			r.setRemote(q.getId(), q.getOrigin());
			server.sendMessage(r);
		} catch (KadException e) {
			ErrorMessage em = new ErrorMessage(q.getMethod(), q.getTxid(), e.getCode(), e.getMessage());
			em.setRemote(q.getId(), q.getOrigin());
			server.sendMessage(em);
		}
	}

	private void onAnnouncePeer(AnnouncePeerRequest q) {
		boolean bogon = Constants.DEVELOPMENT_ENVIRONMENT ?
				!AddressUtils.isAnyUnicast(q.getOrigin().getAddress()) : AddressUtils.isBogon(q.getOrigin());

		if (bogon) {
			log.debug("Received an announce peer request from bogon address {}, ignored ",
					AddressUtils.toString(q.getOrigin()));
			return;
		}

		DataStorage storage = getNode().getStorage();

		if (!getNode().getTokenManager().verifyToken(q.getToken(), q.getId(), q.getOrigin(), q.getTarget())) {
			log.warn("Received an announce peer request with invalid token from {}",
					AddressUtils.toString(q.getOrigin()));
			sendError(q, ErrorCode.ProtocolError.value(), "Invalid token for ANNOUNCE PEER request");
			return;
		}

		PeerInfo peer = q.getPeer();
		try {
			log.debug("Received an announce peer request from {}, saving peer {}", AddressUtils.toString(q.getOrigin()),
					q.getTarget());

			storage.putPeer(peer);
			AnnouncePeerResponse r = new AnnouncePeerResponse(q.getTxid());
			r.setRemote(q.getId(), q.getOrigin());
			server.sendMessage(r);
		} catch (KadException e) {
			sendError(q, e.getCode(), e.getMessage());
		}
	}

	private void onResponse(OldMessage r) {
		// Nothing to do
	}

	private void onError(ErrorMessage e) {
		log.warn("Error from {}/{} - {}:{}, txid {}", AddressUtils.toString(e.getOrigin()), e.getReadableVersion(),
				e.getCode(), e.getMessage(), e.getTxid());
	}

	/**
	 * Increase the failed queries count of the bucket entry we sent the message to.
	 *
	 * @param call the RPC call.
	 */
	protected void onTimeout(RPCCall call) {
		// ignore the timeout if the DHT is stopped or the RPC server is offline
		if (!isRunning() || !server.isReachable())
			return;

		Id nodeId = call.getTargetId();
		routingTable.onTimeout(nodeId);
	}

	protected void onSend(RPCCall call) {
		if (!isRunning())
			return;

		Id nodeId = call.getTargetId();
		routingTable.onSend(nodeId);
	}

	private void sendError(OldMessage q, int code, String msg) {
		ErrorMessage em = new ErrorMessage(q.getMethod(), q.getTxid(), code, msg);
		em.setRemote(q.getId(), q.getOrigin());
		server.sendMessage(em);
	}

	void received(OldMessage msg) {
		InetSocketAddress addr = msg.getOrigin();
		boolean bogon = Constants.DEVELOPMENT_ENVIRONMENT ?
				!AddressUtils.isAnyUnicast(addr.getAddress()) : AddressUtils.isBogon(addr);

		if (bogon) {
			log.debug("Received a message from bogon address {}, ignored the potential routing table operation",
					AddressUtils.toString(addr));
			return;
		}

		Id id = msg.getId();
		RPCCall call = msg.getAssociatedCall();

		// we only want remote nodes with stable ports in our routing table,
		// so apply a stricter check here
		if (call != null && (!call.matchesAddress() || !call.matchesId()))
			return;

		KBucketEntry old = routingTable.getEntry(id, true);
		if (old != null && !old.getAddress().equals(addr)) {
			// this might happen if one node changes ports (broken NAT?) or IP address
			// ignore until routing table entry times out
			return;
		}

		Id knownId = knownNodes.getIfPresent(addr);
		KBucketEntry knownEntry = routingTable.getEntry(id, true);

		if ((knownId != null && !knownId.equals(id)) ||
				(knownEntry != null && !knownEntry.getAddress().equals(addr))) {
			if (knownEntry != null) {
				// 1. a node with that address is in our routing table
				// 2. the ID does not match our routing table entry
				//
				// That means we are certain that the node either changed its
				// node ID or does some ID-spoofing.
				// In either case we don't want it in our routing table
				log.warn("force-removing routing table entry {} because ID-change was detected; new ID {}", knownEntry,
						id);
				routingTable.remove(knownId);

				// might be pollution attack, check other entries in the same bucket too in case
				// random
				// pings can't keep up with scrubbing.
				KBucket bucket = routingTable.bucketOf(knownId);
				routingTable.tryPingMaintenance(bucket, EnumSet.of(PingRefreshTask.Options.checkAll),
						"Checking bucket " + bucket.prefix() + " after ID change was detected");
				knownNodes.put(addr, id);
				return;
			} else {
				knownNodes.invalidate(addr);
			}
		}

		knownNodes.put(addr, id);
		KBucketEntry newEntry = new KBucketEntry(id, addr);
		newEntry.setVersion(msg.getVersion());

		if (call != null) {
			newEntry.signalResponse(call.getRTT());
			newEntry.mergeRequestTime(call.getSentTime());
		} else if (old == null) {
			// Verify the node, speedup the bootstrap process
			PingRequest q = new PingRequest();
			RPCCall c = new RPCCall(newEntry, q);
			// Maybe we are in the RPCSever's callback
			getNode().getScheduler().execute(() -> server.sendCall(c));
		}

		routingTable.put(newEntry);
	}

	private void populateClosestNodes(LookupResponse r, Id target, int v4, int v6) {
		if (v4 > 0) {
			DHT dht4 = type == Network.IPv4 ? this : getNode().getDHT(Network.IPv4);
			if (dht4 != null) {
				KClosestNodes kns = new KClosestNodes(dht4, target, v4);
				kns.fill(this == dht4);
				r.setNodes4(kns.asNodeList());
			}
		}

		if (v6 > 0) {
			DHT dht6 = type == Network.IPv6 ? this : getNode().getDHT(Network.IPv6);
			if (dht6 != null) {
				KClosestNodes kns = new KClosestNodes(dht6, target, v6);
				kns.fill(this == dht6);
				r.setNodes6(kns.asNodeList());
			}
		}
	}

	public Task findNode(Id id, Consumer<NodeInfo> completeHandler) {
		return findNode(id, LookupOption.CONSERVATIVE, completeHandler);
	}

	public Task findNode(Id id, LookupOption option, Consumer<NodeInfo> completeHandler) {
		AtomicReference<NodeInfo> nodeRef = new AtomicReference<>(routingTable.getEntry(id, true));
		NodeLookup task = new NodeLookup(this, id);
		task.setResultHandler((v) -> {
			nodeRef.set(v);

			if (option != LookupOption.CONSERVATIVE) {
				task.cancel();
				return;
			}
		});

		task.addListener(t -> {
			completeHandler.accept(nodeRef.get());
		});

		taskMan.add(task);
		return task;
	}

	public Task findValue(Id id, LookupOption option, Consumer<Value> completeHandler) {
		AtomicReference<Value> valueRef = new AtomicReference<>(null);
		ValueLookup task = new ValueLookup(this, id);
		task.setResultHandler((v) -> {
			if (valueRef.get() == null) {
				valueRef.set(v);
			} else {
				if (valueRef.get().getSequenceNumber() < v.getSequenceNumber())
					valueRef.set(v);
			}

			// all immutable values will stop the lookup
			if (option != LookupOption.CONSERVATIVE || !v.isMutable()) {
				task.cancel();
				return;
			}
		});

		task.addListener(t -> {
			completeHandler.accept(valueRef.get());
		});

		taskMan.add(task);
		return task;
	}

	public Task storeValue(Value value, Consumer<List<NodeInfo>> completeHandler) {
		NodeLookup lookup = new NodeLookup(this, value.getId());
		lookup.setWantToken(true);
		lookup.addListener(l -> {
			if (lookup.getState() != Task.State.FINISHED)
				return;

			ClosestSet closest = lookup.getClosestSet();
			if (closest == null || closest.size() == 0) {
				// this should never happen
				log.warn("!!! Value announce task not started because the node lookup task got the empty closest nodes.");
				completeHandler.accept(Collections.emptyList());
				return;
			}

			ValueAnnounce announce = new ValueAnnounce(this, closest, value);
			announce.addListener(a -> {
				completeHandler.accept(new ArrayList<>(closest.getEntries()));
			});

			lookup.setNestedTask(announce);
			taskMan.add(announce);
		});

		taskMan.add(lookup);
		return lookup;
	}

	public Task findPeer(Id id, int expected, LookupOption option, Consumer<Collection<PeerInfo>> completeHandler) {
		Set<PeerInfo> peers = ConcurrentHashMap.newKeySet();
		PeerLookup task = new PeerLookup(this, id);
		task.setResultHandler((ps) -> {
			peers.addAll(ps);

			if (option != LookupOption.CONSERVATIVE && peers.size() >= expected) {
				task.cancel();
				return;
			}
		});

		task.addListener(t -> {
			completeHandler.accept(peers);
		});

		taskMan.add(task);
		return task;
	}

	public Task announcePeer(PeerInfo peer, Consumer<List<NodeInfo>> completeHandler) {
		NodeLookup lookup = new NodeLookup(this, peer.getId());
		lookup.setWantToken(true);
		lookup.addListener(l -> {
			if (lookup.getState() != Task.State.FINISHED)
				return;

			ClosestSet closest = lookup.getClosestSet();
			if (closest == null || closest.size() == 0) {
				// this should never happen
				log.warn("!!! Peer announce task not started because the node lookup task got the empty closest nodes.");
				completeHandler.accept(Collections.emptyList());
				return;
			}

			PeerAnnounce announce = new PeerAnnounce(this, closest, peer);
			announce.addListener(a -> {
				completeHandler.accept(new ArrayList<>(closest.getEntries()));
			});

			lookup.setNestedTask(announce);
			taskMan.add(announce);
		});

		taskMan.add(lookup);
		return lookup;
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(10240);

		repr.append("DHT: ").append(type);
		repr.append('\n');
		repr.append("Address: ").append(AddressUtils.toString(server.getAddress()));
		repr.append('\n');
		repr.append(routingTable);

		return repr.toString();
	}
}