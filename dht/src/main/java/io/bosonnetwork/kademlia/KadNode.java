package io.bosonnetwork.kademlia;

import java.io.IOException;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.AnnounceFailedException;
import io.bosonnetwork.AnnounceResult;
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
import io.bosonnetwork.kademlia.impl.DHT;
import io.bosonnetwork.kademlia.impl.DHTConnectionStatusListener;
import io.bosonnetwork.kademlia.impl.KadConstants;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TokenManager;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.kademlia.tasks.EligiblePeers;
import io.bosonnetwork.kademlia.tasks.EligibleValue;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Variable;
import io.bosonnetwork.vertx.BosonVerticle;
import io.bosonnetwork.vertx.ContextualFuture;
import io.bosonnetwork.vertx.VertxCaffeine;

@NullMarked
public class KadNode extends BosonVerticle implements Node {
	// This implementation's identity on the wire. Not tuning: NAME and SHORT_NAME are what peers see
	// in the version field, so they belong to this class rather than to KadConstants.
	public static final String NAME = "Orca";
	public static final String SHORT_NAME = "OR";
	public static final int VERSION_NUMBER = 1;
	public static final int VERSION = Version.build(SHORT_NAME, VERSION_NUMBER);

	private final NodeConfiguration config;

	private final CachedCryptoIdentity identity;
	private final @Nullable String host4;
	private final @Nullable String host6;
	private final int port;

	// Written on this node's context during deploy/undeploy, read from any thread by the public
	// accessors below; volatile so a caller cannot observe a stale or half-published DHT.
	private volatile @Nullable DHT dht4;
	private volatile @Nullable DHT dht6;

	private LookupOption defaultLookupOption;

	private  @Nullable Blacklist blacklist;

	private TokenManager tokenManager;
	private DataStorage storage;

	private final List<Long> timers;

	// The re-announce cycle: work selected but not yet started, and how much of it is running. See
	// persistentAnnounce. Confined to the node's context, like everything else the timers touch.
	private final Deque<Supplier<Future<?>>> announceTodo;
	private int announceInFlight;
	private boolean announceDispatching;
	// Bumped by start(), so an item still in flight from a previous deployment cannot decrement the
	// counter belonging to the new one. Such a decrement has no matching increment, so it would not
	// self-correct: the baseline stays low for the life of the node and the budget silently runs wide.
	//
	// No deployment reaches that today - start() refuses a second call, because BosonVerticle.prepare
	// assigns this.vertx and nothing ever clears it, and start() is the only caller that deploys a
	// KadNode. This is kept so the invariant survives that guard changing rather than because the case
	// is live. DHT has the same shape and does allow a direct redeploy, which is why it resets its own
	// per-deployment state in deploy().
	private int announceGeneration;
	// How many items the re-announce runs at once. Derived from concurrentTasks in start(), where
	// KademliaOptions is unwrapped, so nothing below depends on the configuration type.
	private int announceConcurrency;

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

		this.identity = new CachedCryptoIdentity(config.keyPair(), null);
		this.config = config;

		try {
			String h4 = config.listen().host4();
			String if4 = config.listen().networkInterface4();
			if (h4 != null) {
				this.host4 = h4;
			} else if (if4 != null) {
				NetworkInterface nif = AddressUtils.getNetworkInterface(if4);
				if (nif == null)
					throw new IllegalArgumentException("Invalid network interface: " + if4);

				this.host4 = nif.inetAddresses()
						.filter(a -> a instanceof Inet4Address)
						.filter(AddressUtils::isAnyUnicast)
						.findFirst()
						.map(InetAddress::getHostAddress)
						.orElseThrow(() -> new IllegalArgumentException("No applicable IPv4 address found on " + if4));

				log.debug("Network interface {} configured for IPv4, resolved to address: {}", if4, this.host4);
			} else {
				this.host4 = null;
			}

			String h6 = config.listen().host6();
			String if6 = config.listen().networkInterface6();
			if (h6 != null) {
				this.host6 = h6;
			} else if (if6 != null) {
				NetworkInterface nif = AddressUtils.getNetworkInterface(if6);
				if (nif == null)
					throw new IllegalArgumentException("Invalid network interface: " + if6);

				this.host6 = nif.inetAddresses()
						.filter(a -> a instanceof Inet6Address)
						.filter(AddressUtils::isAnyUnicast)
						.findFirst()
						.map(InetAddress::getHostAddress)
						.orElseThrow(() -> new IllegalArgumentException("No applicable IPv6 address found on " + if6));

				log.debug("Network interface {} configured for IPv6, resolved to address: {}", if6, this.host6);
			} else {
				this.host6 = null;
			}

			if (host4 == null && host6 == null)
				throw new IllegalArgumentException("At least one host/address/interface must be specified");
		} catch (Exception e) {
			log.error("Invalid configuration: {}", e.getMessage(), e);
			throw new IllegalArgumentException("Invalid configuration", e);
		}

		this.port = config.listen().port();

		this.defaultLookupOption = LookupOption.CONSERVATIVE;
		this.connectionStatusListener = new ListenerProxy();
		this.running = false;

		this.timers = new ArrayList<>(4);
		this.announceTodo = new ArrayDeque<>();
	}

	// Only what NodeConfiguration itself cannot guarantee. The listen endpoint, the key pair, the
	// port range and the database URI are all validated by the configuration's own constructors, so
	// re-checking them here would be dead code that drifts out of step with the real rule.
	private void checkConfig(NodeConfiguration config) {
		if (config.bootstraps().isEmpty())
			log.warn("No bootstrap nodes are configured");

		// The configuration guarantees a directory but not that it can be used: the routing table
		// caches and the SQLite database file both land under it, so create it now rather than
		// failing halfway through deploy().
		Path dir = config.dataDir();
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

		// NodeConfiguration accepts the URI schemes the project supports; this node also has to have
		// the driver on its own class path.
		if (!DataStorage.supports(config.database().uri()))
			throw new IllegalArgumentException("unsupported storage URL: " + config.database().uri());
	}

	@Override
	public Id getId() {
		return identity.getId();
	}

	@Override
	public Optional<NodeInfo> getNodeInfo() {
		// Read each field once: undeploy clears it, and a second read could see the null.
		DHT d4 = dht4;
		DHT d6 = dht6;
		NodeInfo n4 = d4 != null ? d4.getNodeInfo() : null;
		NodeInfo n6 = d6 != null ? d6.getNodeInfo() : null;
		return Optional.ofNullable(mergeNodeInfo(getId(), n4, n6));
	}

	/**
	 * Retrieves the IPv4 host address associated with this node.
	 *
	 * @return the IPv4 host address as a string, or {@code null} if no IPv4 address is configured.
	 */
	public @Nullable String getHost4() {
		return host4;
	}

	/**
	 * Retrieves the IPv6 host address associated with this node.
	 *
	 * @return the IPv6 host address as a string, or {@code null} if no IPv6 address is configured.
	 */
	public @Nullable String getHost6() {
		return host6;
	}

	/**
	 * Retrieves the port number on which this node is operating.
	 *
	 * @return the port number as an integer.
	 */
	public int getPort() {
		return port;
	}

	/**
	 * Combine the per-family results into a single transport-agnostic {@link NodeInfo}, taking the IPv4
	 * address from {@code n4} and the IPv6 address from {@code n6}. Returns {@code null} if both are null.
	 * <p>
	 * The presence of each address records which family answered: on the returned node,
	 * {@link NodeInfo#hasAddress4()}/{@link NodeInfo#hasAddress6()} are true only for the families that
	 * contributed a result. A dual-stack node that responded on only one family therefore yields a
	 * single-address {@link NodeInfo}.
	 */
	private static @Nullable NodeInfo mergeNodeInfo(Id id, @Nullable NodeInfo n4, @Nullable NodeInfo n6) {
		if (n4 == null && n6 == null)
			return null;

		return NodeInfo.of(id,
				n4 != null ? n4.getAddress4() : null,
				n6 != null ? n6.getAddress6() : null);
	}

	/**
	 * Normalize a lookup result to a plain {@link NodeInfo} before it crosses the public API boundary,
	 * so internal mutable subtypes ({@code KBucketEntry}, {@code CandidateNode}) are never handed to
	 * callers. A plain {@code NodeInfo} (immutable) is returned as-is.
	 */
	private static @Nullable NodeInfo toPublicNodeInfo(@Nullable NodeInfo n) {
		return (n == null || n.getClass() == NodeInfo.class) ? n :
				NodeInfo.of(n.getId(), n.getAddress4(), n.getAddress6());
	}

	/**
	 * Log a warning when exactly one address family's DHT lookup failed while the other succeeded. The
	 * lookup as a whole still succeeds with a partial result, but a persistent single-family outage
	 * (e.g. the IPv6 path is down) is worth surfacing operationally. Both futures must be settled, so
	 * this is only meaningful after a {@code Future.join}.
	 *
	 * @param operation the lookup name, for the log message.
	 * @param target    the lookup target, for the log message.
	 * @param future4   the settled IPv4 lookup future.
	 * @param future6   the settled IPv6 lookup future.
	 */
	private static void logPartialFailure(String operation, Object target, Future<?> future4, Future<?> future6) {
		if (future4.failed() && future6.succeeded())
			log.warn("{} {}: IPv4 DHT lookup failed but IPv6 succeeded; returning partial result",
					operation, target, future4.cause());
		else if (future6.failed() && future4.succeeded())
			log.warn("{} {}: IPv6 DHT lookup failed but IPv4 succeeded; returning partial result",
					operation, target, future6.cause());
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

	/**
	 * Whether this node runs an IPv4 stack.
	 *
	 * @return {@code true} if the IPv4 DHT is deployed.
	 */
	public boolean isIPv4Enabled() {
		return dht4 != null;
	}

	/**
	 * Whether this node runs an IPv6 stack.
	 *
	 * @return {@code true} if the IPv6 DHT is deployed.
	 */
	public boolean isIPv6Enabled() {
		return dht6 != null;
	}

	/**
	 * Returns the internal DHT instance for the given network family, or {@code null} if this node
	 * does not run that family (single-stack node).
	 * <p>
	 * REMARK: this method is not part of the public API, just for internal use. It is package-private
	 * rather than protected on purpose: this class is public and not final, so a protected member
	 * would still publish the internal DHT type to any subclass.
	 *
	 * @param network the network family.
	 * @return the DHT instance, or {@code null} if not enabled for this node.
	 */
	@Nullable DHT getDHT(Network network) {
		return network == Network.IPv4 ? dht4 : dht6;
	}

	/**
	 * Whether any of this node's stacks currently appears able to carry traffic.
	 * <p>
	 * Either stack is enough: an operation on a dual-stack node is announced on both, and one working
	 * family is a working network. Used to skip self-initiated background work, never work the caller
	 * asked for - see {@link DHT#isReachable()}.
	 * </p>
	 *
	 * @return {@code true} if at least one running DHT considers itself reachable.
	 */
	public boolean isReachable() {
		DHT d4 = dht4;
		DHT d6 = dht6;
		return (d4 != null && d4.isReachable()) || (d6 != null && d6.isReachable());
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
			Context ctx = vertxContext;
			String deploymentId = ctx != null ? ctx.deploymentID() : null;
			if (deploymentId == null)
				promise.fail(new IllegalStateException("Not started"));
			else
				ctx.owner().undeploy(deploymentId).onComplete(promise);
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

		String storageURI = config.database().uri();
		// fix the sqlite database file location
		if (storageURI.startsWith("jdbc:sqlite:")) {
			Path dbFile = Path.of(storageURI.substring("jdbc:sqlite:".length()));
			if (!dbFile.isAbsolute())
				storageURI = "jdbc:sqlite:" + config.dataDir().resolve(dbFile).toAbsolutePath();
		}
		storage = DataStorage.create(storageURI, config.database().poolSize(), config.database().schema());

		// TODO: empty blacklist for now
		blacklist = Blacklist.empty();

		// KademliaOptions is unwrapped here and nowhere else: everything below this point receives the
		// individual values, so no DHT-internal component depends on the configuration type.
		final NodeConfiguration.KademliaOptions kademlia = config.kademlia();
		final int alpha = kademlia.alpha();
		final int k = kademlia.k();
		final int replacements = kademlia.replacements();
		final int concurrentTasks = kademlia.concurrentTasks();
		// A quarter of the slots, and not more: an item in flight is a lookup task plus the announce
		// task nested behind it, on each configured stack, so a quarter of the items is about half the
		// runners once dual-stack is counted. See persistentAnnounce for why the bound is on items in
		// flight rather than on items per cycle.
		this.announceConcurrency = Math.max(1, concurrentTasks / 4);

		// Re-announce state is reset here rather than in the constructor, so a redeployed instance starts
		// a clean cycle instead of inheriting the counters of the one it replaced. State whose lifetime
		// is a deployment belongs to the deployment, not to the object.
		this.announceTodo.clear();
		this.announceInFlight = 0;
		this.announceDispatching = false;
		this.announceGeneration++;

		return storage.initialize(vertx, MAX_VALUE_AGE, MAX_PEER_AGE).compose(unused -> {
			ArrayList<Future<Void>> futures = new ArrayList<>(2);
			connectionStatusListener.setContext(vertxContext);
			if (host4 != null) {
				dht4 = new DHT(identity, Network.IPv4, host4, port, config.bootstraps(),
						alpha, k, replacements, concurrentTasks,
						storage, config.dataDir().resolve("dht4.cache"),
						tokenManager, blacklist, config.security().spamThrottling(),
						config.security().suspiciousNodeDetector(), config.security().developerMode(), null);

				dht4.setConnectionStatusListener(connectionStatusListener);
			}

			if (host6 != null) {
				dht6 = new DHT(identity, Network.IPv6, host6, port, config.bootstraps(),
						alpha, k, replacements, concurrentTasks,
						storage, config.dataDir().resolve("dht6.cache"),
						tokenManager, blacklist, config.security().spamThrottling(),
						config.security().suspiciousNodeDetector(), config.security().developerMode(), null);

				dht6.setConnectionStatusListener(connectionStatusListener);
			}

			if (dht4 != null && dht6 != null) {
				// the sibling should be wired before the DHT deploys
				dht4.setSibling(dht6);
				dht6.setSibling(dht4);
			}

			if (dht4 != null) {
				Future<Void> future = vertx.deployVerticle(dht4).andThen(ar -> {
					if (ar.failed()) {
						dht4 = null;
						// unwire the surviving sibling, it must not serve a DHT that failed to deploy
						if (dht6 != null)
							dht6.setSibling(null);
					}
				}).mapEmpty();
				futures.add(future);
			}

			if (dht6 != null) {
				Future<Void> future = vertx.deployVerticle(dht6).andThen(ar -> {
					if (ar.failed()) {
						dht6 = null;
						// unwire the surviving sibling, it must not serve a DHT that failed to deploy
						if (dht4 != null)
							dht4.setSibling(null);
					}
				}).mapEmpty();
				futures.add(future);
			}

			return Future.all(futures);
		}).transform(ar -> {
			if (ar.succeeded()) {
				long timer = vertx.setPeriodic(KadConstants.STORAGE_EXPIRE_INITIAL_DELAY,
						KadConstants.STORAGE_EXPIRE_INTERVAL, unused -> storage.purge());
				timers.add(timer);

				timer = vertx.setPeriodic(KadConstants.RE_ANNOUNCE_INITIAL_DELAY,
						KadConstants.RE_ANNOUNCE_INTERVAL, unused -> persistentAnnounce());
				timers.add(timer);

				// Checked several times per window rather than once: the rotation happens on the first
				// check that finds the window expired, so this period is the overshoot, not the lifetime.
				timer = vertx.setPeriodic(TokenManager.ROTATION_CHECK_INTERVAL, TokenManager.ROTATION_CHECK_INTERVAL,
						unused -> tokenManager.updateTokenTimestamps()
				);
				timers.add(timer);

				running = true;
				log.info("Kademlia node started.");
				return Future.succeededFuture();
			} else {
				log.error("Failed to start Kademlia node.", ar.cause());
				// Tear down whatever did come up - a DHT may have deployed before its sibling failed -
				// and report the original failure only once that teardown has finished. This is the only
				// teardown a failed start gets: the framework's rollback fails the pending start promise
				// and never calls undeploy on a deployable that did not start, so nothing else will
				// release the storage and the sockets. Discarding the future here let the deployment be
				// reported as failed while both were still closing.
				return undeploy().transform(unused -> Future.<Void>failedFuture(ar.cause()));
			}
		});
	}

	/**
	 * Tears the node down: timers, both DHTs, and the storage.
	 * <p>
	 * Called once per deployment, from one of two places that cannot both happen. The framework calls it
	 * when undeploying a verticle that started; the failure branch of {@link #deploy()} calls it directly
	 * when one did not. Those are exclusive because the framework only undeploys an instance whose start
	 * succeeded - its rollback for a failed deployment fails the pending start promise instead, and never
	 * reaches the deployable - so a node that failed to start would be left holding its sockets and its
	 * storage if {@code deploy()} did not clean up after itself.
	 * </p>
	 * <p>
	 * Which also means the direct call is the only teardown on that path, and the failure branch has to
	 * wait for it before reporting the failure rather than fire it and move on.
	 * </p>
	 *
	 * @return a future completed when teardown has finished.
	 */
	@Override
	protected Future<Void> undeploy() {
		running = false;

		return Future.succeededFuture().andThen(ar -> {
			if (!timers.isEmpty()) {
				timers.forEach(vertx::cancelTimer);
				timers.clear();
			}

			// Re-announce work selected but not yet started. Dropped rather than drained: the DHTs are
			// about to go, and the announced times were never moved, so a node that comes back re-selects
			// exactly these items. Leaving it would let a redeployed instance inherit a cycle aimed at
			// DHTs that no longer exist, and would keep persistentAnnounce skipping forever on the
			// still-draining check.
			announceTodo.clear();
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

	/**
	 * Join two per-family lookup futures, tolerating a single-family failure: the result succeeds if
	 * at least one family succeeds, and only fails when both families fail. Used for CONSERVATIVE
	 * lookups that accumulate their results as a side effect.
	 */
	private static Future<Void> joinTolerant(String operation, Object target, Future<Void> future4, Future<Void> future6) {
		// Future.join waits for both to complete; afterwards each original future is settled and can be
		// inspected directly. Succeed if at least one succeeded; fail only if both failed.
		return Future.join(future4, future6).transform(ar -> {
			logPartialFailure(operation, target, future4, future6);
			return (future4.succeeded() || future6.succeeded()) ?
					Future.succeededFuture() : Future.failedFuture(future4.cause());
		});
	}

	private Future<Optional<NodeInfo>> doFindNode(Id id, LookupOption option) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.findNode(id, option).map(n -> Optional.ofNullable(toPublicNodeInfo(n)));
		} else {
			Future<@Nullable NodeInfo> future4 = dht4.findNode(id, option);
			Future<@Nullable NodeInfo> future6 = dht6.findNode(id, option);

			if (option == LookupOption.CONSERVATIVE) {
				// Tolerate a single-family failure: merge whatever succeeded and only fail if BOTH fail.
				return Future.join(future4, future6).transform(ar -> {
					if (!future4.succeeded() && !future6.succeeded())
						return Future.failedFuture(future4.cause());

					logPartialFailure("findNode", id, future4, future6);
					NodeInfo n4 = future4.succeeded() ? future4.result() : null;
					NodeInfo n6 = future6.succeeded() ? future6.result() : null;
					return Future.succeededFuture(Optional.ofNullable(mergeNodeInfo(id, n4, n6)));
				});
			}

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && future4.result() == null)
					return future6.map(Optional::ofNullable);

				if (future6.isComplete() && future6.result() == null)
					return future4.map(Optional::ofNullable);

				NodeInfo n4 = future4.isComplete() ? future4.result() : null;
				NodeInfo n6 = future6.isComplete() ? future6.result() : null;

				return Future.succeededFuture(Optional.ofNullable(mergeNodeInfo(id, n4, n6)));
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
				return joinTolerant("findValue", id, future4, future6);

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && result.isEmpty())
					return future6;

				if (future6.isComplete() && result.isEmpty())
					return future4;

				return Future.succeededFuture();
			});
		}
	}

	@Override
	public ContextualFuture<AnnounceResult> storeValue(Value value, int expectedSequenceNumber, boolean persistent) {
		Objects.requireNonNull(value, "Invalid value");
		checkRunning();

		Promise<AnnounceResult> promise = Promise.promise();

		// The atomic validate-and-store, not a check followed by a separate write. The checks are the
		// same three this method used to run inline - mutability, sequence number, ownership - but run
		// inside putValue's transaction, so two concurrent local stores of the same id cannot interleave
		// between the check and the write. This is the guarantee DHT.onStoreValue already documents for
		// the network path; the local path had been the one without it.
		//
		// failIfNotOwner is true where the network path passes false: a remote store that would displace
		// a value this node owns is silently kept as-is, but a local caller asked for this store and has
		// to be told it did not happen.
		runOnContext(na -> storage.putValue(value, expectedSequenceNumber, persistent, true)
				.onFailure(cause -> log.warn("Rejecting local store of value {}: {}", value.getId(), cause.getMessage()))
				.compose(v -> doStoreValue(value, expectedSequenceNumber))
				.compose(result -> announced(result, "store value " + value.getId())
						? storage.updateValueAnnouncedTime(value.getId()).map(result)
						: Future.succeededFuture(result))
				.onComplete(promise)
		);

		return ContextualFuture.of(promise.future());
	}

	/**
	 * Whether a publish put the payload on the network, warning if it did not.
	 * <p>
	 * This gates the announced timestamp, and the distinction it draws is the one the re-announce cycle
	 * runs on. {@code persistentAnnounce} selects by that timestamp, so moving it forward means "this is
	 * published, leave it alone until the next window". A publish that found nobody to ask completes
	 * successfully - it is the ordinary state of a node that has not finished bootstrapping - but it put
	 * the payload nowhere, and stamping it as announced would hide it from the very cycle meant to try
	 * again, for a full window. Success of the operation and success of the publication are different
	 * questions, and only the second one belongs here.
	 * </p>
	 * <p>
	 * A publish that reached nobody is logged at WARN even though it is not an error: on a node with
	 * peers it should not happen, and it is invisible to a caller that only awaits the future.
	 * </p>
	 *
	 * @param result the publish outcome.
	 * @param what   the operation and payload, for the log line.
	 * @return true if at least one node took it.
	 */
	private boolean announced(AnnounceResult result, String what) {
		if (result.isAnnounced())
			return true;

		log.warn("Could not {}: no node on the network took it ({}). It is held locally and will be "
				+ "offered again if it is persistent.", what, result.status());
		return false;
	}

	private Future<AnnounceResult> doStoreValue(Value value, int expectedSequenceNumber) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.storeValue(value, expectedSequenceNumber);
		} else {
			return mergeFamilies("storeValue", value.getId(),
					dht4.storeValue(value, expectedSequenceNumber),
					dht6.storeValue(value, expectedSequenceNumber));
		}
	}

	/**
	 * Combines a publish over both address families into one result.
	 * <p>
	 * The families are separate networks with separate routing tables, so they reach different nodes and
	 * the union is what the caller asked for. Recomputing the aggregate over that union is what makes
	 * "IPv6 reached nobody" a partial success rather than a failure - which matters because a dual-stack
	 * node whose IPv6 has no reachable peers is an ordinary deployment, not a broken one.
	 * </p>
	 * <p>
	 * A family whose future failed still carries a result on its exception, and it is merged in: those
	 * nodes were asked and their answers belong in the total. Only a failure with nothing attached - the
	 * DHT was not running - has nothing to contribute.
	 * </p>
	 *
	 * @param operation the operation name, for the log line.
	 * @param target    the payload id, for the log line.
	 * @param future4   the IPv4 outcome.
	 * @param future6   the IPv6 outcome.
	 * @return the merged result, failing only if neither family reached anybody.
	 */
	private static Future<AnnounceResult> mergeFamilies(String operation, Object target,
			Future<AnnounceResult> future4, Future<AnnounceResult> future6) {
		return Future.join(future4, future6).transform(ar -> {
			logPartialFailure(operation, target, future4, future6);

			AnnounceResult merged = AnnounceResult.merge(resultOf(future4), resultOf(future6));
			if (!merged.isFailure())
				return Future.succeededFuture(merged);

			return Future.failedFuture(new AnnounceFailedException(
					operation + " " + target + " reached no node: " + merged, merged));
		});
	}

	/** The result a family produced, whether it succeeded or failed carrying one. */
	private static AnnounceResult resultOf(Future<AnnounceResult> future) {
		if (future.succeeded())
			return future.result();

		AnnounceResult result = future.cause() instanceof AnnounceFailedException afe ? afe.getResult() : null;
		return result != null ? result : AnnounceResult.of(List.of());
	}

	@Override
	public ContextualFuture<List<PeerInfo>> findPeer(Id id, int expectedSequenceNumber, int expectedCount, @Nullable LookupOption option) {
		Objects.requireNonNull(id, "Invalid peer id");
		if (expectedSequenceNumber < -1)
			throw new IllegalArgumentException("Invalid sequence number");
		if (!running)
			throw new IllegalStateException("Node is not running");

		final int expectedPeerCount = EligiblePeers.resolveExpectedCount(expectedCount);

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
				return joinTolerant("findPeer", id, future4, future6);

			return Future.any(future4, future6).compose(cf -> {
				if (future4.isComplete() && !result.reachedCapacity())
					return future6;

				if (future6.isComplete() && !result.reachedCapacity())
					return future4;

				return Future.succeededFuture();
			});
		}
	}

	@Override
	public ContextualFuture<AnnounceResult> announcePeer(PeerInfo peer, int expectedSequenceNumber, boolean persistent) {
		Objects.requireNonNull(peer, "Invalid value");
		checkRunning();

		Promise<AnnounceResult> promise = Promise.promise();

		// Atomic validate-and-store, for the reasons given on storeValue above.
		runOnContext(na -> storage.putPeer(peer, expectedSequenceNumber, persistent, true)
				.onFailure(cause -> log.warn("Rejecting local announce of peer {}: {}", peer.getId(), cause.getMessage()))
				.compose(v -> doAnnouncePeer(peer, expectedSequenceNumber))
				// Announced time moves only where something was published - see storeValue.
				.compose(result -> announced(result, "announce peer " + peer.getId())
						? storage.updatePeerAnnouncedTime(peer.getId(), peer.getFingerprint()).map(result)
						: Future.succeededFuture(result))
				.onComplete(promise)
		);

		return ContextualFuture.of(promise.future());
	}

	private Future<AnnounceResult> doAnnouncePeer(PeerInfo peer, int expectedSequenceNumber) {
		if (dht4 == null && dht6 == null)
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		if (dht4 == null || dht6 == null) {
			DHT dht = dht4 != null ? dht4 : dht6;
			return dht.announcePeer(peer, expectedSequenceNumber);
		} else {
			return mergeFamilies("announcePeer", peer.getId(),
					dht4.announcePeer(peer, expectedSequenceNumber),
					dht6.announcePeer(peer, expectedSequenceNumber));
		}
	}

	/**
	 * Re-publishes the values and peers this node is persistently announcing.
	 * <p>
	 * The heaviest periodic work the node does: one full iterative store-or-announce lookup per item
	 * due, so its cost scales with what the application has asked the node to keep published rather
	 * than with anything the DHT controls. It used to start all of them at once. That never produced
	 * hundreds of concurrent lookups - {@code TaskManager} caps what runs at {@code concurrentTasks}
	 * and queues the rest - but it did produce hundreds of queued tasks, four per item once a lookup,
	 * its nested announce and both stacks are counted. That queue is FIFO and undifferentiated, and
	 * every user-facing call site adds without {@code prior}, so a {@code findValue} arriving mid-cycle
	 * waited behind the whole backlog. The bound here is therefore on queue depth, which is what hurts;
	 * bandwidth was already bounded.
	 * </p>
	 * <p>
	 * Work is dispatched {@link #announceConcurrency} items at a time and refilled as each finishes, so
	 * the queue never holds more than that and user work is never more than a few tasks from a runner.
	 * The ordering that makes a bounded cycle safe lives in the selection queries, which return the
	 * least recently announced first - see
	 * {@code SqlDialect.selectValuesByPersistentAndAnnouncedBefore}. Serving a fixed number of the
	 * <i>most</i> recently announced, which is what those queries used to return, would have starved
	 * the items nearest expiry indefinitely.
	 * </p>
	 * <p>
	 * <b>The deadline this has and bucket maintenance does not.</b> A deferred bucket refresh costs
	 * nothing; a deferred announce can cost the item. The selection window opens only
	 * {@code 2 * RE_ANNOUNCE_INTERVAL} before the remote holders expire it, so the eligible set has to
	 * clear in about two cycles or data is dropped by the network. Two things keep that comfortable:
	 * items become eligible spread across their own announce times rather than together, and a cycle
	 * of ten minutes at this concurrency covers far more items than a node is expected to hold. A node
	 * that cannot keep up was already not keeping up - the old code queued the same work behind the
	 * same 32 runners - but it now degrades by falling behind quietly rather than by blocking
	 * everything else the node does.
	 * </p>
	 * <p>
	 * Skipped entirely while unreachable, and nothing is lost by skipping: the announced time is
	 * updated on success only, so the next cycle re-selects the same items.
	 * </p>
	 */
	private void persistentAnnounce() {
		if (!isReachable()) {
			log.info("Skipping the re-announce, no reachable network.");
			return;
		}

		// A cycle still draining is not a reason to select again. The outstanding work is the same
		// items in the same order - the announced times have not moved, because they move on success
		// only - so a second selection would queue duplicates of what is already running and defeat the
		// bound. Falling behind is visible here, and is the one thing worth warning about.
		if (!announceTodo.isEmpty() || announceInFlight > 0) {
			log.warn("Skipping the re-announce, the previous cycle is still running: {} in flight, {} pending.",
					announceInFlight, announceTodo.size());
			return;
		}

		log.info("Re-announce the persistent values and peers...");

		long valuesBefore = System.currentTimeMillis() - MAX_VALUE_AGE + KadConstants.RE_ANNOUNCE_INTERVAL * 2;
		long peersBefore = System.currentTimeMillis() - MAX_PEER_AGE + KadConstants.RE_ANNOUNCE_INTERVAL * 2;

		storage.getValues(true, valuesBefore)
				.compose(values -> storage.getPeers(true, peersBefore).map(peers -> {
					enqueueAnnounces(values, peers);
					return (Void) null;
				}))
				.onSuccess(unused -> dispatchAnnounces())
				.onFailure(e -> log.error("Failed to select the items to re-announce", e));
	}

	/**
	 * Queues one unit of work per item, taking from the two lists alternately.
	 * <p>
	 * Alternating rather than concatenating is what keeps the two kinds from starving each other: both
	 * lists arrive sorted by their own urgency, but they share one budget, and a node holding many more
	 * values than peers would otherwise spend every cycle on values alone.
	 * </p>
	 */
	private void enqueueAnnounces(List<Value> values, List<PeerInfo> peers) {
		int count = Math.max(values.size(), peers.size());
		for (int i = 0; i < count; i++) {
			if (i < values.size()) {
				final Value value = values.get(i);
				announceTodo.addLast(() -> {
					log.debug("Re-announce the value: {}", value.getId());
					return doStoreValue(value, value.getSequenceNumber())
							.compose(v -> storage.updateValueAnnouncedTime(value.getId()))
							.andThen(ar -> {
								if (ar.succeeded())
									log.debug("Re-announce the value {} success", value.getId());
								else
									log.error("Re-announce the value {} failed", value.getId(), ar.cause());
							});
				});
			}

			if (i < peers.size()) {
				final PeerInfo peer = peers.get(i);
				announceTodo.addLast(() -> {
					log.debug("Re-announce the peer: {}", peer.getId());
					return doAnnouncePeer(peer, -1)
							.compose(v -> storage.updatePeerAnnouncedTime(peer.getId(), peer.getFingerprint()))
							.andThen(ar -> {
								if (ar.succeeded())
									log.debug("Re-announce the peer {} success", peer.getId());
								else
									log.error("Re-announce the peer {} failed", peer.getId(), ar.cause());
							});
				});
			}
		}
	}

	/**
	 * Starts as much queued re-announce work as the budget allows, and is called again by each item as
	 * it finishes, so the number in flight holds at the budget until the queue empties.
	 * <p>
	 * Still fire-and-forget per item: each one logs its own outcome and the next cycle re-selects
	 * whatever did not succeed. What is awaited is only the slot, not the result.
	 * </p>
	 */
	private void dispatchAnnounces() {
		// An item whose future is already settled - no DHT deployed, say - completes inside get(), which
		// re-enters here through the completion handler. Returning lets the loop below carry on instead:
		// it re-reads announceInFlight each turn, so the slot the re-entrant call freed is filled by the
		// next iteration rather than by a nested call, and a fully synchronous queue cannot recurse to
		// the depth of the queue.
		if (announceDispatching)
			return;

		announceDispatching = true;
		try {
			final int generation = announceGeneration;
			while (announceInFlight < announceConcurrency && !announceTodo.isEmpty()) {
				Supplier<Future<?>> work = announceTodo.pollFirst();
				announceInFlight++;

				Future<?> started;
				try {
					started = work.get();
				} catch (RuntimeException e) {
					// A slot taken by an item that never started would never be given back, and the
					// still-draining check in persistentAnnounce would then skip every cycle from here on -
					// the re-announce would stop silently, which is the one failure mode worth being
					// careful about on this path.
					log.error("Re-announce item failed to start", e);
					announceInFlight--;
					continue;
				}

				started.onComplete(ar -> {
					// An item outliving the deployment that started it must not touch the new one's budget.
					if (generation != announceGeneration)
						return;

					announceInFlight--;
					dispatchAnnounces();
				});
			}
		} finally {
			announceDispatching = false;
		}
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

	/**
	 * Writes a human-readable dump of the routing table of the given family to {@code out}.
	 * <p>
	 * The dump runs on the owning DHT's context, so it sees the routing table in a consistent state
	 * rather than one being mutated underneath it.
	 * </p>
	 *
	 * @param family the network family to dump, {@link StandardProtocolFamily#INET} or
	 *               {@link StandardProtocolFamily#INET6}.
	 * @param out    the stream to write the dump to; not closed by this method.
	 * @return a future that completes when the dump has been written, failed if this node does not
	 *         run the requested family.
	 */
	public ContextualFuture<Void> dumpRoutingTable(StandardProtocolFamily family, PrintStream out) {
		Objects.requireNonNull(family, "Invalid protocol family");
		Objects.requireNonNull(out, "Invalid output stream");

		Future<Void> future = switch (family) {
			// Read the field once: undeploy clears it, and a second read could see the null.
			case INET -> {
				DHT dht = dht4;
				yield dht != null ? dht.dumpRoutingTable(out) :
						Future.failedFuture(new IllegalStateException("No DHT/IPv4 available"));
			}
			case INET6 -> {
				DHT dht = dht6;
				yield dht != null ? dht.dumpRoutingTable(out) :
						Future.failedFuture(new IllegalStateException("No DHT/IPv6 available"));
			}
			default -> Future.failedFuture(new IllegalArgumentException("Unsupported protocol family"));
		};

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

	/**
	 * Hands out the infrastructure this node was built on: its {@code Vertx} instance, and its
	 * {@link DataStorage}.
	 * <p>
	 * Reaching for the storage this way is reaching past the node. What comes back is the node's own
	 * storage rather than a view of it, so a caller can read, write and delete anything the node has
	 * stored, with none of the checks the DHT paths apply on the way in. The local administration
	 * commands in the shell do exactly that on purpose - inspecting and seeding storage directly is what
	 * they are for - and they are the only callers. Application code has the store, announce and find
	 * methods on this class, and wants those instead.
	 * </p>
	 * <p>
	 * That is also why this is the door rather than a named accessor. The exposure is the same either
	 * way; what a named {@code getStorage()} added was the suggestion that reaching for it is ordinary.
	 * An unwrap call says at the call site that the caller knows it is not.
	 * </p>
	 * <p>
	 * The storage is handed out only while the node is running, which is the check the accessor it
	 * replaced made explicitly. Gating on the field being set is not the same test and is not enough:
	 * deploy creates the storage well before it finishes initializing it and longer still before the
	 * node is running, and undeploy stops running first and closes the storage several steps later, so
	 * both ends of a deployment have a window where the field holds a storage that no caller should be
	 * given. The {@code Vertx} instance above needs no such gate - it belongs to the framework rather
	 * than to this deployment, and is assigned before deploy and never cleared.
	 * </p>
	 *
	 * @param clazz the type of the infrastructure component.
	 * @param <T>   the type parameter.
	 * @return the component, or empty if this node has nothing of that type to give - including a
	 *         storage asked for before the node is running or after it has stopped.
	 */
	@Override
	public <T> Optional<T> unwrap(Class<T> clazz) {
		if (clazz.isInstance(vertx))
			return Optional.of(clazz.cast(vertx));
		if (clazz.isInstance(storage))
			return running ? Optional.of(clazz.cast(storage)) : Optional.empty();

		return Optional.empty();
	}

	@Override
	public String toString() {
		return "Kademlia node: " + identity.getId().toString();
	}

	/**
	 * Folds the per-family status of the two DHTs into the one status a KadNode has, and fans changes
	 * out to the node's listeners.
	 * <p>
	 * Holds its listeners rather than extending the list. What this is, is a listener; being a
	 * {@code List} as well published every list operation to callers that only ever add and remove, and
	 * made it {@code Serializable} - with the {@code serialVersionUID} that obligation carries - for
	 * something that can never meaningfully be serialized.
	 * </p>
	 */
	private static class ListenerProxy implements DHTConnectionStatusListener {
		private final CopyOnWriteArrayList<ConnectionStatusListener> listeners = new CopyOnWriteArrayList<>();

		// Written on the KadNode context but read from the DHT verticles' own event-loop threads.
		private volatile @Nullable Context context;
		private volatile ConnectionStatus status4 = ConnectionStatus.Disconnected;
		private volatile ConnectionStatus status6 = ConnectionStatus.Disconnected;

		void add(ConnectionStatusListener listener) {
			listeners.add(listener);
		}

		void remove(ConnectionStatusListener listener) {
			listeners.remove(listener);
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
			update(network, ConnectionStatus.Connecting);
		}

		@Override
		public void connected(Network network) {
			update(network, ConnectionStatus.Connected);
		}

		@Override
		public void disconnected(Network network) {
			update(network, ConnectionStatus.Disconnected);
		}

		/**
		 * Records one family's new status and, if that moved the node's own status, tells the listeners.
		 * <p>
		 * The transition check is what makes the three directions symmetric. Only the disconnected one
		 * used to have it, so a dual-stack node whose second DHT came up behind the first announced
		 * itself connected twice - two events for one thing happening, from a listener's point of view.
		 * </p>
		 *
		 * @param network the address family that changed.
		 * @param status  its new status.
		 */
		private void update(Network network, ConnectionStatus status) {
			ConnectionStatus previous = nodeStatus();

			if (network.isIPv4())
				status4 = status;
			if (network.isIPv6())
				status6 = status;

			ConnectionStatus current = nodeStatus();
			if (current == previous)
				return;

			for (ConnectionStatusListener listener : listeners) {
				runOnContext(v -> {
					try {
						switch (current) {
							case Connecting -> listener.connecting();
							case Connected -> listener.connected();
							case Disconnected -> listener.disconnected();
						}
					} catch (Exception e) {
						log.error("Error dispatching {} to listener: {}", current, listener, e);
					}
				});
			}
		}

		/**
		 * The node's status, taken as the best of its DHTs': connected if either family is connected,
		 * connecting while either is still trying, disconnected only once both have stopped.
		 *
		 * @return the node-wide connection status.
		 */
		private ConnectionStatus nodeStatus() {
			if (status4 == ConnectionStatus.Connected || status6 == ConnectionStatus.Connected)
				return ConnectionStatus.Connected;

			if (status4 == ConnectionStatus.Connecting || status6 == ConnectionStatus.Connecting)
				return ConnectionStatus.Connecting;

			return ConnectionStatus.Disconnected;
		}
	}
}
