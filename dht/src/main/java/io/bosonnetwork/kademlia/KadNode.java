package io.bosonnetwork.kademlia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.ConnectionStatus;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Node;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.Version;
import io.bosonnetwork.crypto.CachedCryptoIdentity;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.exceptions.ImmutableSubstitutionException;
import io.bosonnetwork.kademlia.exceptions.NotOwnerException;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpectedException;
import io.bosonnetwork.kademlia.impl.DHT;
import io.bosonnetwork.kademlia.impl.DHTConnectionStatusListener;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TokenManager;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.kademlia.tasks.EligiblePeers;
import io.bosonnetwork.kademlia.tasks.EligibleValue;
import io.bosonnetwork.utils.Variable;
import io.bosonnetwork.vertx.BosonVerticle;
import io.bosonnetwork.vertx.ContextualFuture;
import io.bosonnetwork.vertx.VertxCaffeine;

@NullMarked
public class KadNode extends BosonVerticle implements Node {
	public static final String NAME = "Orca";
	public static final String SHORT_NAME = "OR";
	public static final int VERSION_NUMBER = 1;
	public static final int VERSION = Version.build(SHORT_NAME, VERSION_NUMBER);

	public static final int RE_ANNOUNCE_INTERVAL = 5 * 60 * 1000;        // 5 minutes in milliseconds
	public static final int STORAGE_EXPIRE_INTERVAL = 10 * 60 * 1000;    // 10 minutes in milliseconds

	private static final int DEFAULT_EXPECTED_PEER_COUNT = 8;

	private final NodeConfiguration config;

	private final CachedCryptoIdentity identity;

	private @Nullable DHT dht4;
	private @Nullable DHT dht6;

	private LookupOption defaultLookupOption;

	private  @Nullable Blacklist blacklist;

	private TokenManager tokenManager;
	private DataStorage storage;

	private final List<Long> timers;

	private volatile boolean running;
	private ListenerProxy connectionStatusListener;

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

		this.config = config;

		this.defaultLookupOption = LookupOption.CONSERVATIVE;
		this.connectionStatusListener = new ListenerProxy();
		this.running = false;

		this.timers = new ArrayList<>(4);
	}

	private void checkConfig(NodeConfiguration config) {
		Objects.requireNonNull(config.vertx(), "Vertx can not be null");
		Objects.requireNonNull(config.privateKey(), "Private key can not be null");
		if (config.host4() == null && config.host6() == null)
			throw new IllegalArgumentException("At least one host/address must be specified");

		if (config.port() < 0 || config.port() > 65535)
			throw new IllegalArgumentException("Invalid port number: " + config.port());

		if (config.bootstrapNodes().isEmpty())
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

		Objects.requireNonNull(config.databaseUri(), "Database URI can not be null");
		if (!DataStorage.supports(config.databaseUri()))
			throw new IllegalArgumentException("unsupported storage URL: " + config.databaseUri());
	}

	@Override
	public Id getId() {
		return identity.getId();
	}

	@Override
	public Optional<NodeInfo> getNodeInfo() {
		NodeInfo n4 = dht4 != null ? dht4.getNodeInfo() : null;
		NodeInfo n6 = dht6 != null ? dht6.getNodeInfo() : null;
		if (n4 == null && n6 == null)
			return Optional.empty();

		return Optional.of(new NodeInfo(getId(),
				n4 != null ? n4.getAddress4() : null,
				n6 != null ? n6.getAddress6() : null));
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
		Objects.requireNonNull(listener, "listener cannot be null");
		connectionStatusListener.add(listener);
	}

	@Override
	public void removeConnectionStatusListener(ConnectionStatusListener listener) {
		Objects.requireNonNull(listener, "listener cannot be null");
		connectionStatusListener.remove(listener);
	}

	@Override
	public ContextualFuture<Void> start() {
		if (this.vertx != null)
			return ContextualFuture.failedFuture(new IllegalStateException("Already started"));

		Future<Void> future = config.vertx().deployVerticle(this).mapEmpty();
		return ContextualFuture.of(future);
	}

	@Override
	public ContextualFuture<Void> stop() {
		if (!isRunning())
			return ContextualFuture.failedFuture(new IllegalStateException("Not started"));

		Promise<Void> promise = Promise.promise();
		runOnContext(v -> {
			String deploymentId = vertxContext != null ? vertxContext.deploymentID() : null;
			if (deploymentId == null)
				promise.fail(new IllegalStateException("Not started"));

			vertx.undeploy(deploymentId).onComplete(promise);
		});

		return ContextualFuture.of(promise.future());
	}

	@Override
	protected void prepare(Vertx vertx, Context context) {
		super.prepare(vertx, context);
		identity.initCache(VertxCaffeine.newBuilder(vertx)
				.expireAfterAccess(KBucketEntry.OLD_AND_STALE_TIME, TimeUnit.MILLISECONDS));
	}

	@Override
	protected Future<Void> deploy() {
		tokenManager = new TokenManager();

		String storageURI = config.databaseUri();
		// fix the sqlite database file location
		if (storageURI.startsWith("jdbc:sqlite:")) {
			Path dbFile = Path.of(storageURI.substring("jdbc:sqlite:".length()));
			if (!dbFile.isAbsolute())
				storageURI = "jdbc:sqlite:" + config.dataDir().resolve(dbFile).toAbsolutePath();
		}
		storage = DataStorage.create(storageURI, config.databasePoolSize(), config.databaseSchemaName());

		// TODO: empty blacklist for now
		blacklist = Blacklist.empty();

		return storage.initialize(vertx, MAX_VALUE_AGE, MAX_PEER_AGE).compose(unused -> {
			ArrayList<Future<Void>> futures = new ArrayList<>(2);
			connectionStatusListener.setContext(vertxContext);
			if (config.host4() != null) {
				dht4 = new DHT(identity, Network.IPv4, config.host4(), config.port(), config.bootstrapNodes(),
						storage, config.dataDir().resolve("dht4.cache"),
						tokenManager, blacklist, config.enableSuspiciousNodeDetector(),
						config.enableSpamThrottling(), null, config.enableDeveloperMode());

				dht4.setConnectionStatusListener(connectionStatusListener);

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

				dht6.setConnectionStatusListener(connectionStatusListener);

				Future<Void> future = vertx.deployVerticle(dht6).andThen(ar -> {
					if (ar.failed())
						dht6 = null;
				}).mapEmpty();
				futures.add(future);
			}

			return Future.all(futures);
		}).andThen(ar -> {
			if (ar.succeeded()) {
				if (dht4 != null && dht6 != null) {
					dht4.setSibling(dht6);
					dht6.setSibling(dht4);
				}

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
	protected Future<Void> undeploy() {
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

			return Future.all(stopFutures).map(cf -> {
				connectionStatusListener.setContext(null);
				return null;
			});
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
	public ContextualFuture<Void> bootstrap(Collection<NodeInfo> bootstrapNodes) {
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

		return ContextualFuture.of(promise.future());
	}

	@Override
	public ContextualFuture<Optional<NodeInfo>> findNode(Id id, @Nullable LookupOption option) {
		Objects.requireNonNull(id, "Invalid node id");
		checkRunning();

		final LookupOption lookupOption = option == null ? defaultLookupOption : option;

		Promise<Optional<NodeInfo>> promise = Promise.promise();
		runOnContext(v -> doFindNode(id, lookupOption).onComplete(promise));
		return ContextualFuture.of(promise.future());
	}

	private Future<Optional<NodeInfo>> doFindNode(Id id, LookupOption option) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.findNode(id, option).map(Optional::ofNullable);
		} else {
			Future<@Nullable NodeInfo> future4 = dht4.findNode(id, option);
			Future<@Nullable NodeInfo> future6 = dht6.findNode(id, option);

			if (option == LookupOption.CONSERVATIVE)
				return Future.all(future4, future6).map(cf -> {
					NodeInfo n4 = cf.resultAt(0);
					NodeInfo n6 = cf.resultAt(1);

					if (n4 == null && n6 == null)
						return Optional.empty();

					return Optional.of(new NodeInfo(id,
							n4 != null ? n4.getAddress4() : null,
							n6 != null ? n6.getAddress6() : null));
				});

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && future4.result() == null)
					return future6.map(Optional::ofNullable);

				if (future6.isComplete() && future6.result() == null)
					return future4.map(Optional::ofNullable);

				NodeInfo n4 = future4.isComplete() ? future4.result() : null;
				NodeInfo n6 = future6.isComplete() ? future6.result() : null;

				return Future.succeededFuture(Optional.of(new NodeInfo(id,
						n4 != null ? n4.getAddress4() : null,
						n6 != null ? n6.getAddress6() : null)));
			});
		}
	}

	@Override
	public ContextualFuture<Optional<Value>> findValue(Id id, int expectedSequenceNumber, LookupOption option) {
		Objects.requireNonNull(id, "Invalid value id");
		checkRunning();

		final LookupOption lookupOption = option == null ? defaultLookupOption : option;
		Promise<Value> promise = Promise.promise();

		runOnContext(v -> {
			EligibleValue eligible = new EligibleValue(id, expectedSequenceNumber);
			Variable<Value> local = Variable.empty();

			storage.getValue(id).compose(value -> {
				if (value != null) {
					eligible.update(value);

					if (!value.isMutable())
						return Future.succeededFuture(eligible);

					if (lookupOption != LookupOption.CONSERVATIVE && !eligible.isEmpty())
						return Future.succeededFuture(eligible);

					local.set(value);
				}

				return doFindValue(id, expectedSequenceNumber, lookupOption, eligible).map(eligible);
			}).compose(vv -> {
				if (eligible.isEmpty() || (local.isPresent() && eligible.getValue().equals(local.get())))
					return Future.succeededFuture(eligible.getValue());

				return storage.putValue(eligible.getValue());
			}).onComplete(promise);
		});

		return ContextualFuture.of(promise.future().map(Optional::ofNullable));
	}

	private Future<Void> doFindValue(Id id, int expectedSequenceNumber, LookupOption option, EligibleValue result) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.findValue(id, expectedSequenceNumber, option).map(v -> {
				if (v != null)
					result.update(v);
				return null;
			});
		} else {
			Future<Void> future4 = dht4.findValue(id, expectedSequenceNumber, option).map(v -> {
				if (v != null)
					result.update(v);
				return null;
			});
			Future<Void> future6 = dht6.findValue(id, expectedSequenceNumber, option).map(v -> {
				if (v != null)
					result.update(v);
				return null;
			});

			if (option == LookupOption.CONSERVATIVE)
				return Future.all(future4, future6).mapEmpty();

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && result.isEmpty())
					return future6;

				if (future6.isComplete() && result.isEmpty())
					return future4;

				return Future.succeededFuture();
			});
		}
	}

	private Future<Void> checkValue(Value value, int expectedSequenceNumber) {
		return storage.getValue(value.getId()).compose(existing -> {
			if (existing == null)
				return Future.succeededFuture();

			// Immutable check
			if (existing.isMutable() != value.isMutable()) {
				log.warn("Rejecting value {}: cannot replace mismatched mutable/immutable", value.getId());
				return Future.failedFuture(new ImmutableSubstitutionException("Cannot replace mismatched mutable/immutable value"));
			}

			if (expectedSequenceNumber >= 0 && existing.getSequenceNumber() > expectedSequenceNumber) {
				log.warn("Rejecting value {}: sequence number not expected", value.getId());
				return Future.failedFuture(new SequenceNotExpectedException("Sequence number not expected"));
			}

			if (existing.hasPrivateKey() && !value.hasPrivateKey()) {
				log.warn("Rejecting value {}: new value not owned by this node", value.getId());
				return Future.failedFuture(new NotOwnerException("new value no private key"));
			}

			return Future.succeededFuture();
		});
	}

	@Override
	public ContextualFuture<Void> storeValue(Value value, int expectedSequenceNumber, boolean persistent) {
		Objects.requireNonNull(value, "Invalid value");
		checkRunning();

		Promise<Void> promise = Promise.promise();

		runOnContext(na -> checkValue(value, expectedSequenceNumber)
				.compose(v -> storage.putValue(value, persistent))
				.compose(v -> doStoreValue(value, expectedSequenceNumber))
				.compose(v -> storage.updateValueAnnouncedTime(value.getId()))
				.<Void>mapEmpty()
				.onComplete(promise)
		);

		return ContextualFuture.of(promise.future());
	}

	private Future<Void> doStoreValue(Value value, int expectedSequenceNumber) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

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
	public ContextualFuture<List<PeerInfo>> findPeer(Id id, int expectedSequenceNumber, int expectedCount, LookupOption option) {
		Objects.requireNonNull(id, "Invalid peer id");
		if (expectedSequenceNumber < -1)
			throw new IllegalArgumentException("Invalid sequence number");
		if (expectedCount < 0)
			throw new IllegalArgumentException("Invalid expected number of peers");
		if (!running)
			throw new IllegalStateException("Node is not running");

		final int expectedPeerCount = expectedCount == 0 ? DEFAULT_EXPECTED_PEER_COUNT : expectedCount;

		final LookupOption lookupOption = option == null ? defaultLookupOption : option;
		Promise<List<PeerInfo>> promise = Promise.promise();

		runOnContext(v -> {
			EligiblePeers eligible = new EligiblePeers(id, expectedSequenceNumber, expectedPeerCount);

			storage.getPeers(id, expectedSequenceNumber, expectedPeerCount).compose(peers -> {
				eligible.add(peers);

				if (!eligible.isEmpty()) {
					if (lookupOption == LookupOption.LOCAL)
						return Future.succeededFuture(eligible);

					if (lookupOption != LookupOption.CONSERVATIVE && eligible.reachedCapacity())
						return Future.succeededFuture(eligible);
				}

				return doFindPeer(id, expectedSequenceNumber, expectedPeerCount, lookupOption, eligible)
						.map(eligible);
			}).compose(el -> {
				if (eligible.isEmpty())
					return Future.succeededFuture(List.<PeerInfo>of());

				return storage.putPeers(eligible.getPeers()).map(l -> {
					eligible.prune();
					return eligible.getPeers();
				});
			}).onComplete(promise);
		});

		return ContextualFuture.of(promise.future());
	}

	private Future<Void> doFindPeer(Id id, int expectedSequenceNumber, int expectedCount,
											  LookupOption option, EligiblePeers result) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.findPeer(id, expectedSequenceNumber, expectedCount, option).map(peers -> {
				if (!peers.isEmpty())
					result.add(peers);
				return null;
			});
		} else {
			Future<Void> future4 = dht4.findPeer(id, expectedSequenceNumber, expectedCount, option).map(peers -> {
				if (!peers.isEmpty())
					result.add(peers);
				return null;
			});
			Future<Void> future6 = dht6.findPeer(id, expectedSequenceNumber, expectedCount, option).map(peers -> {
				if (!peers.isEmpty())
					result.add(peers);
				return null;
			});

			if (option == LookupOption.CONSERVATIVE)
				return Future.all(future4, future6).mapEmpty();

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && !result.reachedCapacity())
					return future6;

				if (future6.isComplete() && !result.reachedCapacity())
					return future4;

				return Future.succeededFuture();
			});
		}
	}

	private Future<Void> checkPeer(PeerInfo peer, int expectedSequenceNumber) {
		return storage.getPeer(peer.getId(), peer.getFingerprint()).compose(existing -> {
			if (existing == null)
				return Future.succeededFuture();

			if (expectedSequenceNumber >= 0 && existing.getSequenceNumber() > expectedSequenceNumber) {
				log.warn("Rejecting peer {}: sequence number not expected", peer.getId());
				return Future.failedFuture(new SequenceNotExpectedException("Sequence number not expected"));
			}

			if (existing.hasPrivateKey() && !peer.hasPrivateKey()) {
				log.warn("Rejecting peer {}: new peer not owned by this node", peer.getId());
				return Future.failedFuture(new NotOwnerException("new peer no private key"));
			}

			return Future.succeededFuture();
		});
	}

	@Override
	public ContextualFuture<Void> announcePeer(PeerInfo peer, int expectedSequenceNumber, boolean persistent) {
		Objects.requireNonNull(peer, "Invalid value");
		checkRunning();

		Promise<Void> promise = Promise.promise();

		runOnContext(na -> checkPeer(peer, expectedSequenceNumber)
				.compose(v -> storage.putPeer(peer, persistent))
				.compose(v -> doAnnouncePeer(peer, expectedSequenceNumber))
				.compose(v -> storage.updatePeerAnnouncedTime(peer.getId(), peer.getFingerprint()))
				.<Void>mapEmpty()
				.onComplete(promise)
		);

		return ContextualFuture.of(promise.future());
	}

	private Future<Void> doAnnouncePeer(PeerInfo peer, int expectedSequenceNumber) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.announcePeer(peer, expectedSequenceNumber);
		} else {
			Future<Void> future4 = dht4.announcePeer(peer, expectedSequenceNumber);
			Future<Void> future6 = dht6.announcePeer(peer, expectedSequenceNumber);
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
		// Best-effort, fire-and-forget re-announce: each item's chain logs its own outcome; the periodic
		// timer reruns regardless, so we don't aggregate/await the per-item futures.
		storage.getValues(true, before).onSuccess(values -> {
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
		}).onFailure(e ->
				log.error("Failed to re-announce the values", e)
		);

		before = System.currentTimeMillis() - MAX_PEER_AGE + RE_ANNOUNCE_INTERVAL * 2;
		storage.getPeers(true, before).onSuccess(peers -> {
			for (PeerInfo peer : peers) {
				log.debug("Re-announce the peer: {}", peer.getId());
				doAnnouncePeer(peer, -1).compose(v ->
						storage.updatePeerAnnouncedTime(peer.getId(), peer.getFingerprint())
				).andThen(ar -> {
					if (ar.succeeded())
						log.debug("Re-announce the peer {} success", peer.getId());
					else
						log.error("Re-announce the peer {} failed", peer.getId(), ar.cause());
				});
			}
		}).onFailure(e ->
				log.error("Failed to re-announce the peers", e)
		);
	}

	@Override
	public ContextualFuture<Optional<Value>> getValue(Id valueId) {
		Objects.requireNonNull(valueId, "valueId");
		checkRunning();
		Future<Optional<Value>> future = storage.getValue(valueId).map(Optional::ofNullable);
		return ContextualFuture.of(future);
	}

	@Override
	public ContextualFuture<Boolean> removeValue(Id valueId) {
		Objects.requireNonNull(valueId, "valueId");
		checkRunning();
		Future<Boolean> future = storage.removeValue(valueId);
		return ContextualFuture.of(future);
	}

	@Override
	public ContextualFuture<List<PeerInfo>> getPeers(Id peerId) {
		Objects.requireNonNull(peerId, "peerId");
		checkRunning();
		Future<List<PeerInfo>> future = storage.getPeers(peerId);
		return ContextualFuture.of(future);
	}

	@Override
	public ContextualFuture<Boolean> removePeers(Id peerId) {
		Objects.requireNonNull(peerId, "peerId");
		checkRunning();
		Future<Boolean> future = storage.removePeers(peerId);
		return ContextualFuture.of(future);
	}

	@Override
	public ContextualFuture<Optional<PeerInfo>> getPeer(Id peerId, long fingerprint) {
		Objects.requireNonNull(peerId, "peerId");
		checkRunning();
		Future<Optional<PeerInfo>> future = storage.getPeer(peerId, fingerprint).map(Optional::ofNullable);
		return ContextualFuture.of(future);
	}

	@Override
	public ContextualFuture<Boolean> removePeer(Id peerId, long fingerprint) {
		Objects.requireNonNull(peerId, "peerId");
		checkRunning();
		Future<Boolean> future = storage.removePeer(peerId, fingerprint);
		return ContextualFuture.of(future);
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
	public byte[] encrypt(Id receiver, byte[] nonce, byte[] data) throws CryptoException {
		return identity.encrypt(receiver, nonce, data);
	}

	@Override
	public byte[] decrypt(Id sender, byte[] data) throws CryptoException {
		return identity.decrypt(sender, data);
	}

	@Override
	public byte[] decrypt(Id sender, byte[] nonce, byte[] data) throws CryptoException {
		return identity.decrypt(sender, nonce, data);
	}

	@Override
	public CryptoContext createCryptoContext(Id id) throws CryptoException {
		return identity.createCryptoContext(id);
	}

	@Override
	public <T> Optional<T> unwrap(Class<T> clazz) {
		if (clazz.isInstance(vertx))
			return Optional.of(clazz.cast(vertx));

		return Optional.empty();
	}

	@Override
	public String toString() {
		return "Kademlia node: " + identity.getId().toString();
	}

	private static class ListenerProxy extends CopyOnWriteArrayList<ConnectionStatusListener> implements DHTConnectionStatusListener {
		private static final long serialVersionUID = 2740228489813224483L;

		private @Nullable Context context;
		private volatile ConnectionStatus status4 = ConnectionStatus.Disconnected;
		private volatile ConnectionStatus status6 = ConnectionStatus.Disconnected;

		public ListenerProxy() {
			super();
		}

		private void setContext(@Nullable Context context) {
			this.context = context;
		}

		private void runOnContext(Handler<Void> action) {
			Objects.requireNonNull(context, "Vert.x context is not available.");
			context.runOnContext(action);
		}

		@Override
		public void connecting(Network network) {
			if (network.isIPv4())
				status4 = ConnectionStatus.Connecting;
			if (network.isIPv6())
				status6 = ConnectionStatus.Connecting;

			// A KadNode is considered to be connecting if at least one DHT (IPv4 or IPv6) is in the connecting state.
			for (ConnectionStatusListener listener : this) {
				runOnContext(v -> {
					try {
						listener.connecting();
					} catch (Exception e) {
						log.error("Error dispatching connecting to listener: {}", listener, e);
					}
				});
			}
		}

		@Override
		public void connected(Network network) {
			if (network.isIPv4())
				status4 = ConnectionStatus.Connected;
			if (network.isIPv6())
				status6 = ConnectionStatus.Connected;

			// A KadNode is considered to be connected if at least one DHT (IPv4 or IPv6) is in the connected state.
			for (ConnectionStatusListener listener : this) {
				runOnContext(v -> {
					try {
						listener.connected();
					} catch (Exception e) {
						log.error("Error dispatching connected to listener: {}", listener, e);
					}
				});
			}
		}

		@Override
		public void disconnected(Network network) {
			if (network.isIPv4())
				status4 = ConnectionStatus.Disconnected;
			if (network.isIPv6())
				status6 = ConnectionStatus.Disconnected;

			if (status4 != ConnectionStatus.Disconnected || status6 != ConnectionStatus.Disconnected)
				return;

			// A KadNode is considered disconnected only when all enabled DHTs (IPv4 and IPv6) are disconnected.
			for (ConnectionStatusListener listener : this) {
				runOnContext(v -> {
					try {
						listener.disconnected();
					} catch (Exception e) {
						log.error("Error dispatching disconnected to listener: {}", listener, e);
					}
				});
			}
		}
	}
}