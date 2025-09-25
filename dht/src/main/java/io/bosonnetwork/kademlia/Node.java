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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Configuration;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.NodeStatus;
import io.bosonnetwork.NodeStatusListener;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Result;
import io.bosonnetwork.Value;
import io.bosonnetwork.Version;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.exceptions.CryptoError;
import io.bosonnetwork.kademlia.exceptions.IOError;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.storage.deprecated.DataStorage;
import io.bosonnetwork.kademlia.storage.deprecated.SQLiteStorage;
import io.bosonnetwork.kademlia.tasks.Task;
import io.bosonnetwork.kademlia.tasks.TaskFuture;
import io.bosonnetwork.utils.AddressUtils;

/**
 * @hidden
 */
public class Node implements io.bosonnetwork.Node {
	private Configuration config;

	private Signature.KeyPair keyPair;
	private CryptoBox.KeyPair encryptKeyPair;
	private Id id;

	private boolean persistent;
	private Path dataPath;

	private static AtomicInteger schedulerThreadIndex;
	private volatile static ScheduledThreadPoolExecutor defaultScheduler;
	private ScheduledExecutorService scheduler;

	private List<ScheduledFuture<?>> scheduledActions = new ArrayList<>();

	private NetworkEngine networkEngine;

	private DHT dht4;
	private DHT dht6;
	private int numDHTs;
	private LookupOption defaultLookupOption = LookupOption.CONSERVATIVE;

	private LoadingCache<Id, CryptoContext> cryptoContexts;
	private Blacklist blacklist;

	private TokenManager tokenMan;
	private DataStorage storage;

	private NodeStatus status;
	private List<NodeStatusListener> statusListeners;

	private List<ConnectionStatusListener> connectionStatusListeners;

	private static final Logger log = LoggerFactory.getLogger(Node.class);

	public Node(Configuration config) throws KadException {
		if (config.address4() == null && config.address6() == null) {
			log.error("No valid IPv4 or IPv6 address specified");
			throw new IOError("No listening address");
		}

		if (Constants.DEVELOPMENT_ENVIRONMENT)
			log.info("Boson node running in development environment.");

		dataPath = config.dataPath() != null ? config.dataPath().normalize().toAbsolutePath() : null;
		persistent = checkPersistence();

		Path keyFile = persistent ? dataPath.resolve("key") : null;

		// Try to load or initialize the key pair
		// 1. preset private key in the configuration
		if (config.privateKey() != null) {
			keyPair = Signature.KeyPair.fromPrivateKey(config.privateKey());
			writeKeyFile(keyFile);
		}

		// 2. then try to load the existing key
		if (persistent && keyPair == null) {
			// Try to load the existing key
			if (Files.exists(keyFile)) {
				if (Files.isDirectory(keyFile))
					log.warn("Key file path {} is an existing directory. DHT node will not be able to persist node key", keyFile);
				else
					loadKey(keyFile);
			}
		}

		// 3. create a random key pair
		if (keyPair == null) { // no existing key
			keyPair = Signature.KeyPair.random();
			writeKeyFile(keyFile);
		}

		encryptKeyPair = CryptoBox.KeyPair.fromSignatureKeyPair(keyPair);

		id = Id.of(keyPair.publicKey().bytes());
		if (persistent)
			writeIdFile(dataPath.resolve("id"));

		log.info("Boson Kademlia node: {}", id);

		try {
			Path blacklistFile = dataPath.resolve("blacklist.yaml");
			if (persistent && Files.exists(blacklistFile))
				blacklist = Blacklist.load(blacklistFile);
			else
				blacklist = Blacklist.empty();
		} catch (IOException e) {
			throw new IOError("Load blacklist error", e);
		}

		statusListeners = new ArrayList<>();
		connectionStatusListeners = new ArrayList<>();
		tokenMan = new TokenManager();

		setupCryptoBoxesCache();

		status = NodeStatus.Stopped;

		this.config = config;

		this.scheduledActions = new ArrayList<>();
	}

	private boolean checkPersistence() {
		if (dataPath == null) {
			log.info("Storage path disabled, DHT node will not try to persist");
			return false;
		}

		if (Files.exists(dataPath)) {
			if (!Files.isDirectory(dataPath)) {
				log.warn("Storage path {} is not a directory. DHT node will not be able to persist state", dataPath);
				return false;
			} else {
				return true;
			}
		} else {
			try {
				Files.createDirectories(dataPath);
				return true;
			} catch (IOException e) {
				log.warn("Storage path {} can not be created. DHT node will not be able to persist state", dataPath);
				return false;
			}
		}
	}

	private void loadKey(Path keyFile) throws KadException {
		try (InputStream is = Files.newInputStream(keyFile)) {
			byte[] key = is.readAllBytes();
			keyPair = Signature.KeyPair.fromPrivateKey(key);
		} catch (IOException e) {
			throw new IOError("Can not read the key file.", e);
		}
	}

	private void writeKeyFile(Path keyFile) throws KadException {
		if (keyFile != null) {
			try (OutputStream os = Files.newOutputStream(keyFile)) {
				os.write(keyPair.privateKey().bytes());
			} catch (IOException e) {
				throw new IOError("Can not write the key file.", e);
			}
		}
	}

	private void writeIdFile(Path idFile) throws KadException {
		if (idFile != null) {
			try (OutputStream os = Files.newOutputStream(idFile)) {
				os.write(id.toString().getBytes());
			} catch (IOException e) {
				throw new IOError("Can not write the id file.", e);
			}
		}
	}

	private void setupCryptoBoxesCache() {
		CacheLoader<Id, CryptoContext> loader;
		loader = new CacheLoader<>() {
			@Override
			public CryptoContext load(Id id) throws CryptoError {
				return createCryptoContext(id);
			}
		};

		RemovalListener<Id, CryptoContext> listener;
		listener = new RemovalListener<Id, CryptoContext>() {
			@Override
			public void onRemoval(Id id, CryptoContext ctx, RemovalCause cause) {
				ctx.close();
			}
		};

		cryptoContexts = Caffeine.newBuilder()
				.expireAfterAccess(Constants.KBUCKET_OLD_AND_STALE_TIME, TimeUnit.MILLISECONDS)
				.removalListener(listener)
				.build(loader);
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public Result<NodeInfo> getNodeInfo() {
		NodeInfo n4 = null;
		if (dht4 != null) {
			n4 = new NodeInfo(id, dht4.getAddress());
			n4.setVersion(Constants.VERSION);
		}

		NodeInfo n6 = null;
		if (dht6 != null) {
			n6 = new NodeInfo(id, dht6.getAddress());
			n6.setVersion(Constants.VERSION);
		}

		return new Result<>(n4, n6);
	}

	@Override
	public boolean isLocalId(Id id) {
		return this.id.equals(id);
	}

	public Configuration getConfig() {
		return config;
	}

	@Override
	public void setDefaultLookupOption(LookupOption option) {
		defaultLookupOption = option != null ? option : LookupOption.CONSERVATIVE;
	}

	@Override
	public void addStatusListener(NodeStatusListener listener) {
		statusListeners.add(listener);
	}

	@Override
	public void removeStatusListener(NodeStatusListener listener) {
		statusListeners.remove(listener);
	}

	private void setStatus(NodeStatus expected, NodeStatus newStatus) {
		if (this.status.equals(expected)) {
			NodeStatus old = this.status;
			this.status = newStatus;
			if (!statusListeners.isEmpty()) {
				for (NodeStatusListener l : statusListeners) {
					l.statusChanged(newStatus, old);

					switch (newStatus) {
					case Starting:
						l.starting();
						break;

					case Running:
						l.started();
						break;

					case Stopping:
						l.stopping();
						break;

					case Stopped:
						l.stopped();
						break;

					default:
						break;
					}
				}
			}
		} else {
			log.warn("Set node status failed, expected is {}, actual is {}", expected, status);
		}
	}

	@Override
	public void addConnectionStatusListener(ConnectionStatusListener listener) {
		connectionStatusListeners.add(listener);
	}

	@Override
	public void removeConnectionStatusListener(ConnectionStatusListener listener) {
		connectionStatusListeners.remove(listener);
	}

	List<ConnectionStatusListener> getConnectionStatusListeners() {
		return connectionStatusListeners;
	}

	@Override
	public ScheduledExecutorService getScheduler() {
		return scheduler;
	}

	public void setScheduler(ScheduledExecutorService scheduler) {
		this.scheduler = scheduler;
	}

	private static ScheduledExecutorService getDefaultScheduler() {
		if (defaultScheduler == null) {
			schedulerThreadIndex = new AtomicInteger(0);

			int corePoolSize = Math.max(Runtime.getRuntime().availableProcessors(), 4);

			ThreadGroup group = new ThreadGroup("BosonKadNode");
			ThreadFactory factory = (r) -> {
				Thread thread = new Thread(group, r, "KadNode-sc-" + schedulerThreadIndex.getAndIncrement());
				thread.setUncaughtExceptionHandler((t, e) -> {
					log.error("Scheduler thread " + t.getName() + " encounter an uncaught exception.", e);
				});
				thread.setDaemon(true);
				return thread;
			};

			log.info("Creating the default scheduled thread pool executor, CorePoolSize: {}, KeepAliveTime: 20s",
					corePoolSize);

			ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(corePoolSize, factory, (r, e) -> {
				if (e.isShutdown() || e.isTerminated())
					log.warn("Scheduler rejected the task execution because executor is shutdown or terminated: {}", r.toString());
				else if (e.getQueue().remainingCapacity() == 0)
					log.error("Scheduler rejected the task execution because task queue is full: {}", r.toString());
				else
					log.error("Scheduler rejected the task execution because unknown reason: {}", r.toString());

				// TODO: check me!!!
				// throw new RejectedExecutionException("Task " + r.toString() + " rejected from " + e.toString());
			});

			s.setKeepAliveTime(20, TimeUnit.SECONDS);
			s.allowCoreThreadTimeOut(true);
			defaultScheduler = s;
		}

		return defaultScheduler;
	}

	@Override
	public void bootstrap(NodeInfo node) throws KadException {
		checkArgument(node != null, "Invalid bootstrap node");

		bootstrap(List.of(node));
	}

	@Override
	public void bootstrap(Collection<NodeInfo> bootstrapNodes) throws KadException {
		checkArgument(bootstrapNodes != null, "Invalid bootstrap nodes");

		if (dht4 != null)
			dht4.bootstrap(bootstrapNodes);

		if (dht6 != null)
			dht6.bootstrap(bootstrapNodes);
	}

	@Override
	public synchronized void start() throws KadException {
		if (status != NodeStatus.Stopped)
			return;

		setStatus(NodeStatus.Stopped, NodeStatus.Starting);
		log.info("Boson node {} is starting...", id);

		try {
			networkEngine = new NetworkEngine();
			if (this.scheduler == null)
				this.scheduler = getDefaultScheduler();

			Path dbFile = persistent ? dataPath.resolve("node.db") : null;
			storage = SQLiteStorage.open(dbFile, getScheduler());

			if (config.address4() != null) {
				if (!AddressUtils.isAnyUnicast(config.address4()))
					throw new IOError("Invalid DHT/IPv4 address: " + config.address4());

				InetSocketAddress addr4 = new InetSocketAddress(config.address4(), config.port());
				dht4 = new DHT(Network.IPv4, this, addr4);
				if (persistent)
					dht4.enablePersistence(dataPath.resolve("dht4.cache"));

				dht4.start(config.bootstrapNodes() != null ? config.bootstrapNodes() : Collections.emptyList());
				numDHTs++;
			}

			if (config.address6() != null) {
				if (!AddressUtils.isAnyUnicast(config.address6()))
					throw new IOError("Invalid DHT/IPv6 address: " + config.address6());

				InetSocketAddress addr6 = new InetSocketAddress(config.address6(), config.port());
				dht6 = new DHT(Network.IPv6, this, addr6);
				if (persistent)
					dht6.enablePersistence(dataPath.resolve("dht6.cache"));

				dht6.start(config.bootstrapNodes() != null ? config.bootstrapNodes() : Collections.emptyList());
				numDHTs++;
			}

			setStatus(NodeStatus.Starting, NodeStatus.Running);
			log.info("Boson Kademlia node {} started", id);
		} catch (KadException e) {
			setStatus(NodeStatus.Starting, NodeStatus.Stopped);
			throw e;
		}

		scheduledActions.add(getScheduler().scheduleWithFixedDelay(this::persistentAnnounce,
				60000, Constants.RE_ANNOUNCE_INTERVAL, TimeUnit.MILLISECONDS));
	}

	@Override
	public synchronized void stop() {
		if (status == NodeStatus.Stopping || status == NodeStatus.Stopped)
			return;

		setStatus(NodeStatus.Running, NodeStatus.Stopping);

		log.info("Boson Kademlia node {} is stopping...", id);

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

		if (dht4 != null) {
			dht4.stop();
			dht4 = null;
		}

		if (dht6 != null) {
			dht6.stop();
			dht6 = null;
		}

		try {
			storage.close();
		} catch (Exception e) {
			log.error("Close data storage failed", e);
		}

		scheduler.shutdown();

		storage = null;
		networkEngine = null;

		setStatus(NodeStatus.Stopping, NodeStatus.Stopped);
		log.info("Boson Kademlia node {} stopped", id);
	}

	/**
	 * @return the status
	 */
	@Override
	public NodeStatus getStatus() {
		return status;
	}

	private void persistentAnnounce() {
		log.info("Re-announce the persistent values and peers...");

		long ts = System.currentTimeMillis() - Constants.MAX_VALUE_AGE +
				Constants.RE_ANNOUNCE_INTERVAL * 2;
		Stream<Value> vs;
		try {
			vs = storage.getPersistentValues(ts);

			vs.forEach((v) -> {
				log.debug("Re-announce the value: {}", v.getId());

				try {
					storage.updateValueLastAnnounce(v.getId());
				} catch (Exception e) {
					log.error("Can not update last announce timestamp for value", e);
				}

				doStoreValue(v).whenComplete((na, e) -> {
					if (e == null)
						log.debug("Re-announce the value {} success", v.getId());
					else
						log.error("Re-announce the value " + v.getId() + " failed", e);
				});
			});
		} catch (KadException e) {
			log.error("Can not read the persistent values", e);
		}

		ts = System.currentTimeMillis() - Constants.MAX_PEER_AGE +
				Constants.RE_ANNOUNCE_INTERVAL * 2;
		try {
			Stream<PeerInfo> ps = storage.getPersistentPeers(ts);

			ps.forEach((p) -> {
				log.debug("Re-announce the peer: {}", p.getId());

				try {
					storage.updatePeerLastAnnounce(p.getId(), p.getNodeId());
				} catch (Exception e) {
					log.error("Can not update last announce timestamp for peer", e);
				}

				doAnnouncePeer(p).whenComplete((na, e) -> {
					if (e == null)
						log.debug("Re-announce the peer {} success", p.getId());
					else
						log.error("Re-announce the peer " + p.getId() + " failed", e);
				});
			});
		} catch (KadException e) {
			log.error("Can not read the persistent peers", e);
		}
	}

	NetworkEngine getNetworkEngine() {
		return networkEngine;
	}

	DHT getDHT(Network type) {
		return type == Network.IPv4 ? dht4 : dht6;
	}

	public DataStorage getStorage() {
		return storage;
	}

	TokenManager getTokenManager() {
		return tokenMan;
	}

	Blacklist getBlacklist() {
		return blacklist;
	}

	@Override
	public byte[] encrypt(Id recipient, byte[] data) {
		CryptoContext ctx = cryptoContexts.get(recipient);
		return ctx.encrypt(data);
	}

	@Override
	public byte[] decrypt(Id sender, byte[] data) throws CryptoError {
		try {
			CryptoContext ctx = cryptoContexts.get(sender);
			return ctx.decrypt(data);
		} catch (Exception e) {
			throw new CryptoError("can not create the encryption context", e.getCause());
		}
	}

	@Override
	public CryptoContext createCryptoContext(Id id) {
		CryptoBox.PublicKey pk = id.toEncryptionKey();
		CryptoBox box = CryptoBox.fromKeys(pk, encryptKeyPair.privateKey());
		return new CryptoContext(id, box);
	}

	@Override
	public byte[] sign(byte[] data) {
		return Signature.sign(data, keyPair.privateKey());
	}

	@Override
	public boolean verify(byte[] data, byte[] signature) {
		return Signature.verify(data, signature, keyPair.publicKey());
	}

	@Override
	public CompletableFuture<Result<NodeInfo>> findNode(Id id, LookupOption option) {
		checkState(isRunning(), "Node not running");
		checkArgument(id != null, "Invalid node id");

		LookupOption lookupOption = option == null ? defaultLookupOption : option;

		TemporalResult<NodeInfo> result = new TemporalResult<>(
				dht4 != null ? dht4.getNode(id) : null,
				dht6 != null ? dht6.getNode(id) : null);

		if (lookupOption == LookupOption.ARBITRARY && result.hasValue())
			return CompletableFuture.completedFuture(result);

		TaskFuture<Result<NodeInfo>> future = new TaskFuture<>();
		AtomicInteger completion = new AtomicInteger(0);

		Consumer<NodeInfo> completeHandler = (n) -> {
			int c = completion.incrementAndGet();

			if (n != null)
				result.setValue(Network.of(n.getAddress()), n);

			if ((lookupOption == LookupOption.OPTIMISTIC && n != null) || c >= numDHTs)
				future.complete(result);
		};

		Task t4 = null, t6 = null;
		if (dht4 != null) {
			t4 = dht4.findNode(id, option, completeHandler);
			future.addTask(t4);
		}

		if (dht6 != null) {
			t6 = dht6.findNode(id, option, completeHandler);
			future.addTask(t6);
		}

		return future;
	}

	@Override
	public CompletableFuture<Value> findValue(Id id, LookupOption option) {
		checkState(isRunning(), "Node not running");
		checkArgument(id != null, "Invalid value id");

		LookupOption lookupOption = option == null ? defaultLookupOption : option;

		Value local = null;
		try {
			local = getStorage().getValue(id);
			if (local != null && (lookupOption == LookupOption.ARBITRARY || !local.isMutable()))
				return CompletableFuture.completedFuture(local);
		} catch (KadException e) {
			return CompletableFuture.failedFuture(e);
		}

		TaskFuture<Value> future = new TaskFuture<>();
		AtomicInteger completion = new AtomicInteger(0);
		AtomicReference<Value> valueRef = new AtomicReference<>(local);

		// TODO: improve the value handler
		Consumer<Value> completeHandler = (v) -> {
			int c = completion.incrementAndGet();

			if (v != null) {
				synchronized(valueRef) {
					if (valueRef.get() == null) {
						valueRef.set(v);
					} else {
						if (!v.isMutable() || (v.isMutable() && valueRef.get().getSequenceNumber() < v.getSequenceNumber()))
							valueRef.set(v);
					}
				}
			}

			if ((lookupOption == LookupOption.OPTIMISTIC && v != null) || c >= numDHTs) {
				Value value = valueRef.get();
				if (value != null) {
					try {
						getStorage().putValue(value);
					} catch (KadException e) {
						log.error("Save value " + id + " failed", e);
					}
				}

				future.complete(value);
			}
		};

		Task t4 = null, t6 = null;
		if (dht4 != null) {
			t4 = dht4.findValue(id, lookupOption, completeHandler);
			future.addTask(t4);
		}

		if (dht6 != null) {
			t6 = dht6.findValue(id, lookupOption, completeHandler);
			future.addTask(t6);
		}

		return future;
	}

	@Override
	public CompletableFuture<Void> storeValue(Value value, boolean persistent) {
		checkState(isRunning(), "Node not running");
		checkArgument(value != null, "Invalid value: null");
		checkArgument(value.isValid(), "Invalid value");

		try {
			getStorage().putValue(value, persistent);
		} catch(KadException e) {
			return CompletableFuture.failedFuture(e);
		}

		return doStoreValue(value);
	}

	private CompletableFuture<Void> doStoreValue(Value value) {
		TaskFuture<Void> future = new TaskFuture<>();
		AtomicInteger completion = new AtomicInteger(0);

		// TODO: improve the complete handler, check the announced nodes
		Consumer<List<NodeInfo>> completeHandler = (nl) -> {
			if (completion.incrementAndGet() >= numDHTs)
				future.complete(null);
		};

		Task t4 = null, t6 = null;
		if (dht4 != null) {
			t4 = dht4.storeValue(value, completeHandler);
			future.addTask(t4);
		}

		if (dht6 != null) {
			t6 = dht6.storeValue(value, completeHandler);
			future.addTask(t6);
		}

		return future;
	}

	@Override
	public CompletableFuture<List<PeerInfo>> findPeer(Id id, int expected, LookupOption option) {
		checkState(isRunning(), "Node not running");
		checkArgument(id != null, "Invalid peer id");

		LookupOption lookupOption = option == null ? defaultLookupOption : option;

		List<PeerInfo> local;
		try {
			local = getStorage().getPeer(id, expected);
			if (((expected <= 0 && !local.isEmpty()) || (expected > 0 && local.size() >= expected)) &&
					lookupOption == LookupOption.ARBITRARY)
				return CompletableFuture.completedFuture(local);
		} catch (KadException e) {
			return CompletableFuture.failedFuture(e);
		}

		TaskFuture<List<PeerInfo>> future = new TaskFuture<>();
		AtomicInteger completion = new AtomicInteger(0);

		Set<PeerInfo> results = ConcurrentHashMap.newKeySet();
		results.addAll(local);

		Consumer<Collection<PeerInfo>> completeHandler = (ps) -> {
			int c = completion.incrementAndGet();

			results.addAll(ps);

			try {
				// TODO: CHECKME! overwrite the local existing directly?!
				getStorage().putPeer(ps);
			} catch (KadException e) {
				log.error("Save peer " + id + " failed", e);
			}

			if (c >= numDHTs) {
				ArrayList<PeerInfo> list = new ArrayList<>(results);
				Collections.shuffle(list);
				future.complete(list);
			}
		};

		Task t4 = null, t6 = null;
		if (dht4 != null) {
			t4 = dht4.findPeer(id, expected, lookupOption, completeHandler);
			future.addTask(t4);
		}

		if (dht6 != null) {
			t6 = dht6.findPeer(id, expected, lookupOption, completeHandler);
			future.addTask(t6);
		}

		return future;
	}

	@Override
	public CompletableFuture<Void> announcePeer(PeerInfo peer, boolean persistent) {
		checkState(isRunning(), "Node not running");
		checkArgument(peer != null, "Invalid peer: null");
		checkArgument(peer.getNodeId().equals(getId()), "Invalid peer: not belongs to current node");
		checkArgument(peer.isValid(), "Invalid peer");

		try {
			getStorage().putPeer(peer, persistent);
		} catch(KadException e) {
			return CompletableFuture.failedFuture(e);
		}

		return doAnnouncePeer(peer);
	}

	private CompletableFuture<Void> doAnnouncePeer(PeerInfo peer) {
		TaskFuture<Void> future = new TaskFuture<>();
		AtomicInteger completion = new AtomicInteger(0);

		// TODO: improve the complete handler, check the announced nodes
		Consumer<List<NodeInfo>> completeHandler = (nl) -> {
			if (completion.incrementAndGet() >= numDHTs)
				future.complete(null);
		};

		Task t4 = null, t6 = null;
		if (dht4 != null) {
			t4 = dht4.announcePeer(peer, completeHandler);
			future.addTask(t4);
		}

		if (dht6 != null) {
			t6 = dht6.announcePeer(peer, completeHandler);
			future.addTask(t6);
		}

		return future;
	}

	@Override
	public CompletableFuture<Value> getValue(Id valueId) {
		checkArgument(valueId != null, "Invalid value id");

		try {
			Value result = getStorage().getValue(valueId);
			return CompletableFuture.completedFuture(result);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public CompletableFuture<Boolean> removeValue(Id valueId) {
		checkArgument(valueId != null, "Invalid value id");

		try {
			boolean result = getStorage().removeValue(valueId);
			return CompletableFuture.completedFuture(result);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public CompletableFuture<PeerInfo> getPeer(Id peerId) {
		checkArgument(peerId != null, "Invalid peer id");

		try {
			PeerInfo result = getStorage().getPeer(peerId, this.getId());
			return CompletableFuture.completedFuture(result);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public CompletableFuture<Boolean> removePeer(Id peerId) {
		checkArgument(peerId != null, "Invalid peer id");

		try {
			boolean result = getStorage().removePeer(peerId, this.getId());
			return CompletableFuture.completedFuture(result);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public String getVersion() {
		return Version.toString(Constants.VERSION);
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(10240);

		repr.append("Node: ").append(id);
		repr.append('\n');
		if (dht4 != null)
			repr.append(dht4);
		if (dht6 != null)
			repr.append(dht6);

		return repr.toString();
	}
}