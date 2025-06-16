package io.bosonnetwork.identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import io.vertx.core.Vertx;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.bosonnetwork.BosonException;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Node;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.NodeStatus;
import io.bosonnetwork.NodeStatusListener;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Result;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Hash;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DHTRegistryTest {
	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "IdentifierRegistryTest");
	private static Vertx vertx = Vertx.vertx();
	private static Node node;
	private static Registry registry;

	@BeforeAll
	public static void setup() throws Exception {
		node = new Node() {
			private Identity identity = new CryptoIdentity();
			private Map<Id, Value> values = new ConcurrentHashMap<>();

			@Override
			public Id getId() {
				return identity.getId();
			}

			@Override
			public Result<NodeInfo> getNodeInfo() {
				return null;
			}

			@Override
			public boolean isLocalId(Id id) {
				return false;
			}

			@Override
			public void setDefaultLookupOption(LookupOption option) {

			}

			@Override
			public void addStatusListener(NodeStatusListener listener) {

			}

			@Override
			public void removeStatusListener(NodeStatusListener listener) {

			}

			@Override
			public void addConnectionStatusListener(ConnectionStatusListener listener) {

			}

			@Override
			public void removeConnectionStatusListener(ConnectionStatusListener listener) {

			}

			@Override
			public ScheduledExecutorService getScheduler() {
				return null;
			}

			@Override
			public void bootstrap(NodeInfo node) throws BosonException {

			}

			@Override
			public void bootstrap(Collection<NodeInfo> bootstrapNodes) throws BosonException {

			}

			@Override
			public void start() throws BosonException {

			}

			@Override
			public void stop() {

			}

			@Override
			public NodeStatus getStatus() {
				return null;
			}

			@Override
			public byte[] sign(byte[] data) {
				return new byte[0];
			}

			@Override
			public boolean verify(byte[] data, byte[] signature) {
				return false;
			}

			@Override
			public byte[] encrypt(Id recipient, byte[] data) {
				return new byte[0];
			}

			@Override
			public byte[] decrypt(Id sender, byte[] data) throws BosonException {
				return new byte[0];
			}

			@Override
			public CryptoContext createCryptoContext(Id id) {
				return null;
			}

			@Override
			public CompletableFuture<Result<NodeInfo>> findNode(Id id, LookupOption option) {
				return null;
			}

			@Override
			public CompletableFuture<Value> findValue(Id id, LookupOption option) {
				return values.get(id) == null ? CompletableFuture.completedFuture(null) : CompletableFuture.completedFuture(values.get(id));
			}

			@Override
			public CompletableFuture<Void> storeValue(Value value, boolean persistent) {
				var updated = values.compute(value.getId(), (k, v) -> {
					if (v != null) {
						if (v.isMutable() && value.getSequenceNumber() < v.getSequenceNumber())
							return v;
						else
							return value;
					} else {
						return value;
					}
				});

				return CompletableFuture.completedFuture(null);
			}

			@Override
			public CompletableFuture<List<PeerInfo>> findPeer(Id id, int expected, LookupOption option) {
				return null;
			}

			@Override
			public CompletableFuture<Void> announcePeer(PeerInfo peer, boolean persistent) {
				return null;
			}

			@Override
			public CompletableFuture<Value> getValue(Id valueId) {
				return null;
			}

			@Override
			public CompletableFuture<Boolean> removeValue(Id valueId) {
				return null;
			}

			@Override
			public CompletableFuture<PeerInfo> getPeer(Id peerId) {
				return null;
			}

			@Override
			public CompletableFuture<Boolean> removePeer(Id peerId) {
				return null;
			}

			@Override
			public String getVersion() {
				return "";
			}
		};

		registry = Registry.DHTRegistry();
		registry.initialize(vertx, node, ResolverCache.fileSystem()).get();
	}

	@AfterAll
	public static void cleanup() throws Exception {
		node.stop();
	}

	private static Identity alice;

	@Test
	@Order(1)
	public void testRegister() throws Exception {
		alice = new CryptoIdentity();

		var card = new CardBuilder(alice)
				.addCredential("profile", "BosonProfile", "name", "Alice",
						"avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.addService("homeNode", "BosonHomeNode", Id.random().toString())
				.build();

		int version = 1;
		var nonce = CryptoBox.Nonce.random();
		var sha = Hash.sha256();
		sha.update(nonce.bytes());
		sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(version).array());
		sha.update(card.toBytes());
		var sig = alice.sign(sha.digest());

		registry.register(card, nonce, version, sig).get();
	}

	@Test
	@Order(2)
	public void testResolve() throws Exception {
		var result = registry.getResolver().resolve(alice.getId()).get();
		assertEquals(Resolver.ResolutionStatus.SUCCESS, result.getResolutionStatus());
		assertNotNull(result.getResult());
		assertNotNull(result.getResultMetadata());

		assertEquals(1, result.getResultMetadata().getVersion());

		var card = result.getResult();
		assertEquals(alice.getId(), card.getId());
		assertEquals(1, card.getCredentials().size());
		assertEquals(1, card.getServices().size());
		assertTrue(card.isGenuine());
	}

	@Test
	@Order(3)
	public void testUpdate() throws Exception {
		var card = new CardBuilder(alice)
				.addCredential("profile", "BosonProfile", "name", "Alice",
						"avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.addCredential("email", "Email", "email", "alice@example.com")
				.addService("homeNode", "BosonHomeNode", Id.random().toString())
				.addService("bms", "BMS", Id.random().toString())
				.build();

		int version = 2;
		var nonce = CryptoBox.Nonce.random();
		var sha = Hash.sha256();
		sha.update(nonce.bytes());
		sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(version).array());
		sha.update(card.toBytes());
		var sig = alice.sign(sha.digest());

		registry.register(card, nonce, version, sig).get();
	}

	@Test
	@Order(4)
	public void testResolve2() throws Exception {
		var result = registry.getResolver().resolve(alice.getId()).get();
		assertEquals(Resolver.ResolutionStatus.SUCCESS, result.getResolutionStatus());
		assertNotNull(result.getResult());
		assertNotNull(result.getResultMetadata());

		assertEquals(1, result.getResultMetadata().getVersion());

		result = registry.getResolver().resolve(alice.getId(), new Resolver.ResolutionOptions(false, 0)).get();

		assertEquals(Resolver.ResolutionStatus.SUCCESS, result.getResolutionStatus());
		assertNotNull(result.getResult());
		assertNotNull(result.getResultMetadata());

		assertEquals(2, result.getResultMetadata().getVersion());

		var card = result.getResult();
		assertEquals(alice.getId(), card.getId());
		assertEquals(2, card.getCredentials().size());
		assertEquals(2, card.getServices().size());
		assertTrue(card.isGenuine());
	}

	@Test
	@Order(5)
	public void testNotFound() throws Exception {
		var result = registry.getResolver().resolve(Id.random()).get();

		assertEquals(Resolver.ResolutionStatus.NOT_FOUND, result.getResolutionStatus());
		assertNull(result.getResult());
		assertNull(result.getResultMetadata());
	}
}