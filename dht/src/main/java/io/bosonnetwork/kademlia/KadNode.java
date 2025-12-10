package io.bosonnetwork.kademlia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.ConnectionStatus;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Network;
import io.bosonnetwork.Node;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Result;
import io.bosonnetwork.Value;
import io.bosonnetwork.Version;
import io.bosonnetwork.crypto.CachedCryptoIdentity;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.impl.DHT;
import io.bosonnetwork.kademlia.impl.SimpleNodeConfiguration;
import io.bosonnetwork.kademlia.impl.TokenManager;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.utils.Variable;
import io.bosonnetwork.vertx.BosonVerticle;
import io.bosonnetwork.vertx.VertxCaffeine;
import io.bosonnetwork.vertx.VertxFuture;

public class KadNode extends BosonVerticle implements Node {
	public static final String NAME = "Orca";
	public static final String SHORT_NAME = "OR";
	public static final int VERSION_NUMBER = 1;
	public static final int VERSION = Version.build(SHORT_NAME, VERSION_NUMBER);

	public static final int MAX_PEER_AGE = 120 * 60 * 1000;                // 2 hours in milliseconds
	public static final int MAX_VALUE_AGE = 120 * 60 * 1000;            // 2 hours in milliseconds
	public static final int RE_ANNOUNCE_INTERVAL = 5 * 60 * 1000;        // 5 minutes in milliseconds
	public static final int STORAGE_EXPIRE_INTERVAL = 10 * 60 * 1000;    // 10 minutes in milliseconds

	private final SimpleNodeConfiguration config;

	private final CachedCryptoIdentity identity;

	private DHT dht4;
	private DHT dht6;

	private LookupOption defaultLookupOption;

	private Blacklist blacklist;

	private TokenManager tokenManager;
	private DataStorage storage;

	private final List<Long> timers;

	private volatile boolean running;
	private ConnectionStatusListener connectionStatusListener;

	private static final Logger log = LoggerFactory.getLogger(KadNode.class);

	public KadNode(NodeConfiguration config) {
		Objects.requireNonNull(config, "Configuration can not be null");
		try {
			checkConfig(config);
		} catch (Exception e) {
			log.error("Invalid configuration: {}", e.getMessage(), e);
			throw new IllegalArgumentException("Invalid configuration", e);
		}

		try {
			Signature.KeyPair keyPair = Signature.KeyPair.fromPrivateKey(config.privateKey());
			this.identity = new CachedCryptoIdentity(keyPair, null);
		} catch (Exception e) {
			log.error("Invalid configuration: private key is invalid");
			throw new IllegalArgumentException("Invalid configuration: private key is invalid", e);
		}

		this.config = new SimpleNodeConfiguration(config);

		this.defaultLookupOption = LookupOption.CONSERVATIVE;
		this.running = false;

		this.timers = new ArrayList<>(4);
	}

	private void checkConfig(NodeConfiguration config) {
		if (config.privateKey() == null)
			throw new IllegalArgumentException("Private key can not be null");

		if (config.host4() == null && config.host6() == null)
			throw new IllegalArgumentException("At least one host/address must be specified");

		if (config.port() < 0 || config.port() > 65535)
			throw new IllegalArgumentException("Invalid port number: " + config.port());

		if (config.bootstrapNodes() == null || config.bootstrapNodes().isEmpty())
			log.warn("No bootstrap nodes are configured");

		Path dir = config.dataDir();
		if (dir != null) {
			if (Files.exists(dir)) {
				if (!Files.isDirectory(dir)) {
					log.error("Data path {} is not a directory", dir);
					throw new IllegalArgumentException("Data path " + dir + " is not a directory");
				}
			} else {
				try {
					Files.createDirectories(dir);
				} catch (IOException e) {
					log.error("Data path {} can not be created", dir);
					throw new IllegalArgumentException("Data path " + dir + " can not be created", e);
				}
			}
		}

		if (config.storageURI() != null) {
			if (!DataStorage.supports(config.storageURI()))
				throw new IllegalArgumentException("unsupported storage URL: " + config.storageURI());
		} else {
			log.warn("No storage URL is configured, in-memory storage is used");
		}
	}

	@Override
	public Id getId() {
		return identity.getId();
	}

	@Override
	public Result<NodeInfo> getNodeInfo() {
		return Result.of(dht4 != null ? dht4.getNodeInfo() : null, dht6 != null ? dht6.getNodeInfo() : null);
	}

	@Override
	public String getVersion() {
		return NAME + "/" + VERSION_NUMBER;
	}

	@Override
	public void setDefaultLookupOption(LookupOption option) {
		this.defaultLookupOption = option;
	}

	@Override
	public LookupOption getDefaultLookupOption() {
		return defaultLookupOption;
	}

	public DHT getDHT(Network network) {
		return network == Network.IPv4 ? dht4 : dht6;
	}

	@Override
	public void addConnectionStatusListener(ConnectionStatusListener listener) {
		assert (listener != null) : "Invalid listener";
		if (this.connectionStatusListener == null) {
			this.connectionStatusListener = listener;
		} else {
			if (this.connectionStatusListener instanceof ListenerArray listeners)
				listeners.add(listener);
			else
				this.connectionStatusListener = new ListenerArray(this.connectionStatusListener, listener);
		}
	}

	@Override
	public void removeConnectionStatusListener(ConnectionStatusListener listener) {
		ConnectionStatusListener current = this.connectionStatusListener;
		if (current == listener)
			this.connectionStatusListener = null;
		else if (current instanceof ListenerArray listeners)
			listeners.remove(listener);
	}

	@Override
	public VertxFuture<Void> start() {
		if (this.vertx != null)
			return VertxFuture.failedFuture(new IllegalStateException("Already started"));

		Future<Void> future = config.vertx().deployVerticle(this).mapEmpty();
		return VertxFuture.of(future);
	}

	@Override
	public VertxFuture<Void> stop() {
		if (!isRunning())
			return VertxFuture.failedFuture(new IllegalStateException("Not started"));

		Promise<Void> promise = Promise.promise();
		runOnContext(v -> {
			String deploymentId = vertxContext != null ? vertxContext.deploymentID() : null;
			if (deploymentId == null)
				promise.fail(new IllegalStateException("Not started"));

			vertx.undeploy(deploymentId).onComplete(promise);
		});

		return VertxFuture.of(promise.future());
	}

	@Override
	public void prepare(Vertx vertx, Context context) {
		super.prepare(vertx, context);
		identity.initCache(VertxCaffeine.newBuilder(vertx)
				.expireAfterAccess(KBucketEntry.OLD_AND_STALE_TIME, TimeUnit.MILLISECONDS));
	}

	@Override
	public Future<Void> deploy() {
		tokenManager = new TokenManager();

		String storageURI = config.storageURI();
		// fix the sqlite database file location
		if (storageURI.startsWith("jdbc:sqlite:")) {
			Path dbFile = Path.of(storageURI.substring("jdbc:sqlite:".length()));
			if (!dbFile.isAbsolute())
				storageURI = "jdbc:sqlite:" + config.dataDir().resolve(dbFile).toAbsolutePath();
		}
		storage = DataStorage.create(storageURI);

		// TODO: empty blacklist for now
		blacklist = Blacklist.empty();

		ConnectionStatusListener listener = new ConnectionStatusListener() {
			@Override
			public void statusChanged(Network network, ConnectionStatus newStatus, ConnectionStatus oldStatus) {
				if (connectionStatusListener != null)
					runOnContext(unused -> connectionStatusListener.statusChanged(network, newStatus, oldStatus));
			}

			@Override
			public void connecting(Network network) {
				if (connectionStatusListener != null)
					runOnContext(unused -> connectionStatusListener.connecting(network));
			}

			@Override
			public void connected(Network network) {
				if (connectionStatusListener != null)
					runOnContext(unused -> connectionStatusListener.connected(network));
			}

			@Override
			public void disconnected(Network network) {
				if (connectionStatusListener != null)
					runOnContext(unused -> connectionStatusListener.disconnected(network));
			}
		};

		return storage.initialize(vertx, MAX_VALUE_AGE, MAX_PEER_AGE).compose(unused -> {
			ArrayList<Future<Void>> futures = new ArrayList<>(2);
			if (config.host4() != null) {
				dht4 = new DHT(identity, Network.IPv4, config.host4(), config.port(), config.bootstrapNodes(),
						storage, config.dataDir().resolve("dht4.cache"),
						tokenManager, blacklist, config.enableSuspiciousNodeDetector(),
						config.enableSpamThrottling(), null, config.enableDeveloperMode());

				dht4.setConnectionStatusListener(listener);

				Future<Void> future = vertx.deployVerticle(dht4).andThen(ar -> {
					if (ar.failed())
						dht4 = null;
				}).mapEmpty();

				futures.add(future);
			}

			if (config.host6() != null) {
				dht6 = new DHT(identity, Network.IPv6, config.host6(), config.port(), config.bootstrapNodes(),
						storage, config.dataDir().resolve("dht6.cache"),
						tokenManager, blacklist, config.enableSuspiciousNodeDetector(),
						config.enableSpamThrottling(), null, config.enableDeveloperMode());

				dht6.setConnectionStatusListener(listener);

				Future<Void> future = vertx.deployVerticle(dht6).andThen(ar -> {
					if (ar.failed())
						dht6 = null;
				}).mapEmpty();
				futures.add(future);
			}

			return Future.all(futures);
		}).andThen(ar -> {
			if (ar.succeeded()) {
				long timer = vertx.setPeriodic(30_000, STORAGE_EXPIRE_INTERVAL, unused -> storage.purge());
				timers.add(timer);

				timer = vertx.setPeriodic(60_000, RE_ANNOUNCE_INTERVAL, unused -> persistentAnnounce());
				timers.add(timer);

				timer = vertx.setPeriodic(TokenManager.TOKEN_TIMEOUT, TokenManager.TOKEN_TIMEOUT, unused ->
						tokenManager.updateTokenTimestamps()
				);
				timers.add(timer);

				running = true;
				log.info("Kademlia node started.");
			} else {
				undeploy();
				log.error("Failed to start Kademlia node.", ar.cause());
			}
		}).mapEmpty();
	}

	@Override
	public Future<Void> undeploy() {
		running = false;

		return Future.succeededFuture().andThen(ar -> {
			if (!timers.isEmpty()) {
				timers.forEach(vertx::cancelTimer);
				timers.clear();
			}
		}).compose(v -> {
			List<Future<Void>> stopFutures = new ArrayList<>(2);

			if (dht4 != null) {
				Future<Void> future = vertx.undeploy(dht4.deploymentID())
						.andThen(ar -> dht4 = null)
						.otherwiseEmpty();
				stopFutures.add(future);
			} else {
				stopFutures.add(Future.succeededFuture());
			}

			if (dht6 != null) {
				Future<Void> future = vertx.undeploy(dht6.deploymentID())
						.andThen(ar -> dht6 = null)
						.otherwiseEmpty();
				stopFutures.add(future);
			} else {
				stopFutures.add(Future.succeededFuture());
			}

			return Future.all(stopFutures).mapEmpty();
		}).compose(v ->
			storage == null ? Future.succeededFuture() :
					storage.close().andThen(ar -> storage = null).otherwiseEmpty()
		).andThen(ar -> {
			tokenManager = null;
			identity.clearCache();
		});
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void checkRunning() {
		if (!running)
			throw new IllegalStateException("Node is not running");
	}

	@Override
	public VertxFuture<Void> bootstrap(Collection<NodeInfo> bootstrapNodes) {
		Objects.requireNonNull(bootstrapNodes, "Invalid bootstrap nodes");
		checkRunning();

		Promise<Void> promise = Promise.promise();

		runOnContext(v -> {
			if (dht4 == null || dht6 == null) {
				DHT dht = dht4 != null ? dht4 : dht6;
				dht.bootstrap(bootstrapNodes).onComplete(promise);
			} else {
				List<Future<Void>> futures = new ArrayList<>(2);
				futures.add(dht4.bootstrap(bootstrapNodes));
				futures.add(dht6.bootstrap(bootstrapNodes));
				Future.all(futures).onComplete(ar -> {
					if (ar.succeeded())
						promise.complete();
					else
						promise.fail(ar.cause());
				});
			}
		});

		return VertxFuture.of(promise.future());
	}

	@Override
	public VertxFuture<Result<NodeInfo>> findNode(Id id, LookupOption option) {
		Objects.requireNonNull(id, "Invalid node id");
		checkRunning();

		final LookupOption lookupOption = option == null ? defaultLookupOption : option;

		Promise<Result<NodeInfo>> promise = Promise.promise();
		runOnContext(v -> doFindNode(id, lookupOption).onComplete(promise));
		return VertxFuture.of(promise.future());
	}

	private Future<Result<NodeInfo>> doFindNode(Id id, LookupOption option) {
		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.findNode(id, option).map(ni -> Result.ofNetwork(dht.getNetwork(), ni));
		} else {
			Future<NodeInfo> future4 = dht4.findNode(id, option);
			Future<NodeInfo> future6 = dht6.findNode(id, option);

			if (option == LookupOption.LOCAL || option == LookupOption.CONSERVATIVE)
				return Future.all(future4, future6).map(cf ->
						Result.of(cf.resultAt(0), cf.resultAt(1))
				);

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && future4.result() == null)
					return future6.map(ni -> Result.of(null, ni));

				if (future6.isComplete() && future6.result() == null)
					return future4.map(ni -> Result.of(ni, null));

				return Future.succeededFuture(Result.of(future4.isComplete() ? future4.result() : null,
						future6.isComplete() ? future6.result() : null));
			});
		}
	}

	@Override
	public VertxFuture<Value> findValue(Id id, int expectedSequenceNumber, LookupOption option) {
		Objects.requireNonNull(id, "Invalid value id");
		checkRunning();

		final LookupOption lookupOption = option == null ? defaultLookupOption : option;
		Promise<Value> promise = Promise.promise();

		runOnContext(v -> {
			Variable<Value> localValue = Variable.empty();

			storage.getValue(id).map(local -> {
				if (local == null)
					return null;

				localValue.set(local);
				if (!local.isMutable())
					return local;

				if (expectedSequenceNumber >= 0 && local.getSequenceNumber() >= expectedSequenceNumber)
					return local;

				return null;
			}).compose(local -> {
				if (lookupOption == LookupOption.LOCAL)
					return Future.succeededFuture(local);

				if (local != null && (!local.isMutable() || lookupOption != LookupOption.CONSERVATIVE))
					return Future.succeededFuture(local);

				return doFindValue(id, expectedSequenceNumber, lookupOption).map(value -> {
					if (value == null && local == null)
						return null;

					if (value == null || local == null)
						return value == null ? local : value;

					return value.getSequenceNumber() > local.getSequenceNumber() ? value : local;
				});
			}).compose(value -> {
				if (value != null && value != localValue.orElse(null))
					return storage.putValue(value);

				return Future.succeededFuture(value);
			}).onComplete(promise);
		});

		return VertxFuture.of(promise.future());
	}

	private Value valueSelector(CompositeFuture future) {
		Value value4 = future.isComplete(0) ? future.resultAt(0) : null;
		Value value6 = future.isComplete(1) ? future.resultAt(1) : null;

		if (value4 == null && value6 == null)
			return null;

		if (value4 == null || value6 == null)
			return value4 == null ? value6 : value4;

		return value4.getSequenceNumber() > value6.getSequenceNumber() ? value4 : value6;
	}

	private Future<Value> doFindValue(Id id, int expectedSequenceNumber, LookupOption option) {
		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.findValue(id, expectedSequenceNumber, option);
		} else {
			Future<Value> future4 = dht4.findValue(id, expectedSequenceNumber, option);
			Future<Value> future6 = dht6.findValue(id, expectedSequenceNumber, option);

			if (option == LookupOption.CONSERVATIVE)
				return Future.all(future4, future6).map(this::valueSelector);

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && future4.result() == null)
					return future6;

				if (future6.isComplete() && future6.result() == null)
					return future4;

				return Future.succeededFuture(valueSelector(cf));
			});
		}
	}

	@Override
	public VertxFuture<Void> storeValue(Value value, int expectedSequenceNumber, boolean persistent) {
		Objects.requireNonNull(value, "Invalid value");
		checkRunning();

		Promise<Void> promise = Promise.promise();

		runOnContext(na ->
				storage.putValue(value, persistent, expectedSequenceNumber).compose(v ->
						doStoreValue(value, expectedSequenceNumber)
				).compose(v ->
						storage.updateValueAnnouncedTime(value.getId()).map((Void) null)
				).onComplete(promise)
		);

		return VertxFuture.of(promise.future());
	}

	private Future<Void> doStoreValue(Value value, int expectedSequenceNumber) {
		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.storeValue(value, expectedSequenceNumber);
		} else {
			Future<Void> future4 = dht4.storeValue(value, expectedSequenceNumber);
			Future<Void> future6 = dht6.storeValue(value, expectedSequenceNumber);
			return Future.all(future4, future6).mapEmpty();
		}
	}

	@Override
	public VertxFuture<List<PeerInfo>> findPeer(Id id, int expected, LookupOption option) {
		Objects.requireNonNull(id, "Invalid peer id");
		if (!running)
			throw new IllegalStateException("Node is not running");

		final LookupOption lookupOption = option == null ? defaultLookupOption : option;
		Promise<List<PeerInfo>> promise = Promise.promise();

		runOnContext(v -> {
			Variable<List<PeerInfo>> localPeers = Variable.empty();

			storage.getPeers(id).compose(local -> {
				localPeers.set(local);

				if (lookupOption == LookupOption.LOCAL)
					return Future.succeededFuture(local);

				if (local.size() >= expected && lookupOption != LookupOption.CONSERVATIVE)
					return Future.succeededFuture(local);

				return doFindPeer(id, expected, lookupOption).map(peers -> {
					if (local.isEmpty() && peers.isEmpty())
						return Collections.<PeerInfo>emptyList();

					if (local.isEmpty() || peers.isEmpty())
						return local.isEmpty() ? peers : local;

					Map<Id, PeerInfo> dedup = new HashMap<>(16);
					local.forEach(peer -> dedup.put(peer.getNodeId(), peer));
					peers.forEach(peer -> dedup.put(peer.getNodeId(), peer));
					return new ArrayList<>(dedup.values());
				});
			}).compose(peers -> {
				if (!peers.isEmpty() && peers != localPeers.orElse(null))
					return storage.putPeers(peers);

				return Future.succeededFuture(peers.isEmpty() ? Collections.emptyList() : peers);
			}).onComplete(promise);
		});

		return VertxFuture.of(promise.future());
	}

	private List<PeerInfo> mergePeers(CompositeFuture future) {
		Map<Id, PeerInfo> dedup = new HashMap<>(16);
		if (future.isComplete(0)) {
			List<PeerInfo> peers = future.resultAt(0);
			peers.forEach(peer -> dedup.put(peer.getNodeId(), peer));
		}

		if (future.isComplete(1)) {
			List<PeerInfo> peers = future.resultAt(1);
			peers.forEach(peer -> dedup.put(peer.getNodeId(), peer));
		}

		return new ArrayList<>(dedup.values());
	}

	private Future<List<PeerInfo>> doFindPeer(Id id, int expected, LookupOption option) {
		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.findPeer(id, expected, option);
		} else {
			Future<List<PeerInfo>> future4 = dht4.findPeer(id, expected, option);
			Future<List<PeerInfo>> future6 = dht6.findPeer(id, expected, option);

			if (option == LookupOption.CONSERVATIVE)
				return Future.all(future4, future6).map(this::mergePeers);

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && future4.result().size() < expected)
					return Future.all(future4, future6).map(this::mergePeers);

				if (future6.isComplete() && future6.result().size() < expected)
					return Future.all(future4, future6).map(this::mergePeers);

				return Future.succeededFuture(mergePeers(cf));
			});
		}
	}

	@Override
	public VertxFuture<Void> announcePeer(PeerInfo peer, boolean persistent) {
		Objects.requireNonNull(peer, "Invalid value");
		checkRunning();

		Promise<Void> promise = Promise.promise();

		runOnContext(na ->
				storage.putPeer(peer, persistent).compose(v ->
						doAnnouncePeer(peer)
				).compose(v ->
						storage.updatePeerAnnouncedTime(peer.getId(), identity.getId()).map((Void) null)
				).onComplete(promise)
		);

		return VertxFuture.of(promise.future());
	}

	private Future<Void> doAnnouncePeer(PeerInfo peer) {
		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.announcePeer(peer);
		} else {
			Future<Void> future4 = dht4.announcePeer(peer);
			Future<Void> future6 = dht6.announcePeer(peer);
			return Future.all(future4, future6).mapEmpty();
		}
	}

	public DataStorage getStorage() {
		checkRunning();
		return storage;
	}

	public <T> Future<T> execute(Callable<T> action) {
		Objects.requireNonNull(action, "Invalid action");
		checkRunning();

		Promise<T> promise = Promise.promise();
		runOnContext(v -> {
			try {
				T result = action.call();
				promise.complete(result);
			} catch (Exception e) {
				promise.fail(e);
			}
		});
		return promise.future();
	}

	private void persistentAnnounce() {
		log.info("Re-announce the persistent values and peers...");

		long before = System.currentTimeMillis() - MAX_VALUE_AGE + RE_ANNOUNCE_INTERVAL * 2;
		storage.getValues(true, before).map(values -> {
			for (Value value : values) {
				log.debug("Re-announce the value: {}", value.getId());
				doStoreValue(value, value.getSequenceNumber()).compose(v ->
						storage.updateValueAnnouncedTime(value.getId())
				).andThen(ar -> {
					if (ar.succeeded())
						log.debug("Re-announce the value {} success", value.getId());
					else
						log.error("Re-announce the value {} failed", value.getId(), ar.cause());
				});
			}

			return null;
		}).onFailure(e ->
				log.error("Failed to re-announce the values", e)
		);

		before = System.currentTimeMillis() - MAX_PEER_AGE + RE_ANNOUNCE_INTERVAL * 2;
		storage.getPeers(true, before).map(peers -> {
			for (PeerInfo peer : peers) {
				log.debug("Re-announce the peer: {}", peer.getId());
				doAnnouncePeer(peer).compose(v ->
						storage.updatePeerAnnouncedTime(peer.getId(), identity.getId())
				).andThen(ar -> {
					if (ar.succeeded())
						log.debug("Re-announce the peer {} success", peer.getId());
					else
						log.error("Re-announce the peer {} failed", peer.getId(), ar.cause());
				});
			}

			return null;
		}).onFailure(e ->
				log.error("Failed to re-announce the peers", e)
		);
	}

	@Override
	public VertxFuture<Value> getValue(Id valueId) {
		Objects.requireNonNull(valueId, "valueId");
		checkRunning();
		Future<Value> future = storage.getValue(valueId);
		return VertxFuture.of(future);
	}

	@Override
	public VertxFuture<Boolean> removeValue(Id valueId) {
		Objects.requireNonNull(valueId, "valueId");
		checkRunning();
		Future<Boolean> future = storage.removeValue(valueId);
		return VertxFuture.of(future);
	}

	@Override
	public VertxFuture<PeerInfo> getPeer(Id peerId) {
		Objects.requireNonNull(peerId, "peerId");
		checkRunning();
		Future<PeerInfo> future = storage.getPeer(peerId, this.getId());
		return VertxFuture.of(future);
	}

	@Override
	public VertxFuture<Boolean> removePeer(Id peerId) {
		Objects.requireNonNull(peerId, "peerId");
		checkRunning();
		Future<Boolean> future = storage.removePeer(peerId, this.getId());
		return VertxFuture.of(future);
	}

	@Override
	public byte[] sign(byte[] data) {
		return identity.sign(data);
	}

	@Override
	public boolean verify(byte[] data, byte[] signature) {
		return identity.verify(data, signature);
	}

	@Override
	public byte[] encrypt(Id recipient, byte[] data) throws CryptoException {
		return identity.encrypt(recipient, data);
	}

	@Override
	public byte[] decrypt(Id sender, byte[] data) throws CryptoException {
		return identity.decrypt(sender, data);
	}

	@Override
	public CryptoContext createCryptoContext(Id id) throws CryptoException {
		return identity.createCryptoContext(id);
	}

	@Override
	public String toString() {
		return "Kademlia node: " + identity.getId().toString();
	}

	private static class ListenerArray extends ArrayList<ConnectionStatusListener> implements ConnectionStatusListener {
		private static final long serialVersionUID = 2740228489813224483L;

		public ListenerArray(ConnectionStatusListener existing, ConnectionStatusListener newListener) {
			super();
			add(existing);
			add(newListener);
		}

		public void statusChanged(Network network, ConnectionStatus newStatus, ConnectionStatus oldStatus) {
			for (ConnectionStatusListener listener : this)
				listener.statusChanged(network, newStatus, oldStatus);
		}

		public void connecting(Network network) {
			for (ConnectionStatusListener listener : this)
				listener.connecting(network);
		}

		public void connected(Network network) {
			for (ConnectionStatusListener listener : this)
				listener.connected(network);
		}

		public void disconnected(Network network) {
			for (ConnectionStatusListener listener : this)
				listener.disconnected(network);
		}
	}
}