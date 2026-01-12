package io.bosonnetwork.kademlia.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import net.datafaker.Faker;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.FileUtils;

@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings({"CodeBlock2Expr", "unused"})
public class DataStorageTests {
	private static final Path testRoot = Path.of(System.getProperty("java.io.tmpdir"), "boson");
	private static final Path testDir = Path.of(testRoot.toString(), "dht", "DataStorageTests");

	private static final int CURRENT_SCHEMA_VERSION = 1;

	private static final Faker faker = new Faker();

	private static final long valueExpiration = TimeUnit.MINUTES.toMillis(1);
	private static final long peerInfoExpiration = TimeUnit.MINUTES.toMillis(1);

	private static DataStorage inMemoryStorage;
	private static DataStorage sqliteStorage;
	private static DataStorage postgresStorage;

	private static List<Value> values;
	private static List<Value> persistentValues;

	private static List<PeerInfo> peerInfos;
	private static List<PeerInfo> persistentPeerInfos;
	private static Map<Id, List<PeerInfo>> multiPeers;
	private static Map<Id, CryptoIdentity> nodeIdentities;

	private static long announceTime1;
	private static long announceTime2;

	private static PostgresqlServer pgServer;
	private static final List<Arguments> dataStorages = new ArrayList<>();

	@BeforeAll
	static void setupDataStorage(Vertx vertx, VertxTestContext context) {
		try {
			FileUtils.deleteFile(testDir);
			Files.createDirectories(testDir);
		} catch (IOException e) {
			context.failNow(e);
		}

		var futures = new ArrayList<Future<Integer>>();

		try {
			pgServer = PostgresqlServer.start("boson_node", "test", "secret");
		} catch (Exception e) {
			System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
			System.err.println("Start PostgreSQL container failed: " + e.getMessage());
			System.err.println("Check your Docker installation.");
			System.err.println("Skipping Postgres tests.");
			System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
		}

		if (pgServer != null) {
			var postgresqlURI = pgServer.getDatabaseUrl();
			postgresStorage = new PostgresStorage(postgresqlURI);
			var future1 = postgresStorage.initialize(vertx, valueExpiration, peerInfoExpiration).onComplete(context.succeeding(version -> {
				context.verify(() -> assertEquals(CURRENT_SCHEMA_VERSION, version));
				dataStorages.add(Arguments.of("PostgresStorage", postgresStorage));
			}));
			futures.add(future1);
		}

		var sqliteURI = "jdbc:sqlite:" + testDir.resolve("storage.db");
		sqliteStorage = new SQLiteStorage(sqliteURI);
		var future2 = sqliteStorage.initialize(vertx, valueExpiration, peerInfoExpiration).onComplete(context.succeeding(version -> {
			context.verify(() -> assertEquals(CURRENT_SCHEMA_VERSION, version));
			dataStorages.add(Arguments.of("SQLiteStorage", sqliteStorage));
		}));
		futures.add(future2);

		Future.all(futures).onSuccess(unused -> {
			try {
				nodeIdentities = IntStream.range(0, 32).mapToObj(i -> new CryptoIdentity())
						.collect(Collectors.toMap(Identity::getId, identity -> identity));

				values = generateValues(Random.random().nextInt(32, 64));
				persistentValues = generateValues(Random.random().nextInt(32, 64));

				peerInfos = generatePeerInfos(Random.random().nextInt(32, 64));
				persistentPeerInfos = generatePeerInfos(Random.random().nextInt(32, 64));
				multiPeers = generateMultiPeerInfos(Random.random().nextInt(2, 6), Random.random().nextInt(16, 32));
			} catch (Exception e) {
				context.failNow(e);
			}
		}).onComplete(context.succeedingThenComplete());
	}

	@AfterAll
	static void tearDown(Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<Void>>();

		for (var storageArg : dataStorages) {
			var args = storageArg.get();
			DataStorage storage1 = (DataStorage) args[1];
			var future = storage1.close();
			futures.add(future);
		}

		Future.all(futures).onComplete(context.succeeding(result -> {
			if (pgServer != null)
				pgServer.stop();
			context.completeNow();
		}));
	}

	private static List<Value> generateValues(int count) throws Exception {
		var values = new ArrayList<Value>(count);
		for (int i = 0; i < count; i++) {
			var type = i % 6;
			var value = switch (type) {
				case 0 -> Value.builder()
						.data(faker.lorem().paragraph().getBytes())
						.build();
				case 1 -> Value.builder()
						.sequenceNumber(faker.number().numberBetween(2, 100))
						.data(faker.lorem().paragraph().getBytes())
						.buildSigned();
				case 2 -> Value.builder()
						.recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
						.sequenceNumber(faker.number().numberBetween(2, 100))
						.data(faker.lorem().paragraph().getBytes())
						.buildEncrypted();
				case 3 -> Value.builder()
						.data(faker.lorem().paragraph().getBytes())
						.build()
						.withoutPrivateKey();
				case 4 -> Value.builder()
						.sequenceNumber(faker.number().numberBetween(2, 100))
						.data(faker.lorem().paragraph().getBytes())
						.buildSigned()
						.withoutPrivateKey();
				case 5 -> Value.builder()
						.recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
						.sequenceNumber(faker.number().numberBetween(2, 100))
						.data(faker.lorem().paragraph().getBytes())
						.buildEncrypted()
						.withoutPrivateKey();
				default -> throw new IllegalStateException();
			};

			values.add(value);
		}

		return values;
	}

	private static List<PeerInfo> generatePeerInfos(int count) {
		var peerInfos = new ArrayList<PeerInfo>(count);
		for (int i = 0; i < count; i++) {
			var type = i % 8;
			var peerInfo = switch (type) {
				case 0 -> PeerInfo.builder()
						.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
						.build();
				case 1 -> PeerInfo.builder()
						.endpoint(faker.internet().url())
						.extra(Map.of("foo", 1234, "bar", "baz"))
						.build();
				case 2 -> PeerInfo.builder()
						.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
						.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
						.build();
				case 3 -> PeerInfo.builder()
						.endpoint(faker.internet().url())
						.extra(Map.of("foo", 1234, "bar", "baz"))
						.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
						.build();
				case 4 -> PeerInfo.builder()
						.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
						.build()
						.withoutPrivateKey();
				case 5 -> PeerInfo.builder()
						.endpoint(faker.internet().url())
						.extra(Map.of("foo", 1234, "bar", "baz"))
						.build()
						.withoutPrivateKey();
				case 6 -> PeerInfo.builder()
						.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
						.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
						.build()
						.withoutPrivateKey();
				case 7 -> PeerInfo.builder()
						.endpoint(faker.internet().url())
						.extra(Map.of("foo", 1234, "bar", "baz"))
						.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
						.build()
						.withoutPrivateKey();
				default -> throw new IllegalStateException();
			};

			peerInfos.add(peerInfo);
		}
		return peerInfos;
	}

	private static Map<Id, List<PeerInfo>> generateMultiPeerInfos(int count, int size) {
		var multiPeers = new HashMap<Id, List<PeerInfo>>();

		for (var j = 0; j < count; j++) {
			var peerInfos = new ArrayList<PeerInfo>(count);
			var keyPair = Signature.KeyPair.random();
			count = Random.random().nextInt(8, 20);
			for (int i = 0; i < size; i++) {
				var peerInfo = switch (i % 8) {
					case 0 -> PeerInfo.builder()
							.fingerprint(Random.random().nextLong())
							.key(keyPair)
							.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
							.build();
					case 1 -> PeerInfo.builder()
							.key(keyPair)
							.fingerprint(Random.random().nextLong())
							.endpoint(faker.internet().url())
							.extra(Map.of("foo", 1234, "bar", "baz"))
							.build();
					case 2 -> PeerInfo.builder()
							.key(keyPair)
							.fingerprint(Random.random().nextLong())
							.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
							.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
							.build();
					case 3 -> PeerInfo.builder()
							.key(keyPair)
							.fingerprint(Random.random().nextLong())
							.endpoint(faker.internet().url())
							.extra(Map.of("foo", 1234, "bar", "baz"))
							.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
							.build();
					case 4 -> PeerInfo.builder()
							.key(keyPair)
							.fingerprint(Random.random().nextLong())
							.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
							.build()
							.withoutPrivateKey();
					case 5 -> PeerInfo.builder()
							.key(keyPair)
							.fingerprint(Random.random().nextLong())
							.endpoint(faker.internet().url())
							.extra(Map.of("foo", 1234, "bar", "baz"))
							.build()
							.withoutPrivateKey();
					case 6 -> PeerInfo.builder()
							.key(keyPair)
							.fingerprint(Random.random().nextLong())
							.endpoint("tcp://" + faker.internet().ipV4Address() + ":" + faker.internet().port())
							.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
							.build()
							.withoutPrivateKey();
					case 7 -> PeerInfo.builder()
							.key(keyPair)
							.fingerprint(Random.random().nextLong())
							.endpoint(faker.internet().url())
							.extra(Map.of("foo", 1234, "bar", "baz"))
							.node(nodeIdentities.values().stream().toList().get(Random.random().nextInt(nodeIdentities.size())))
							.build()
							.withoutPrivateKey();
					default -> throw new IllegalStateException();
				};

				peerInfos.add(peerInfo);
			}

			multiPeers.put(Id.of(keyPair.publicKey().bytes()), peerInfos);
		}

		return multiPeers;
	}

	static Stream<Arguments> testStoragesProvider() {
		return dataStorages.stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(0)
	void testGetSchemaVersion(String name, DataStorage storage) {
		assertEquals(CURRENT_SCHEMA_VERSION, storage.getSchemaVersion());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(1)
	void testPutValue(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		Future<Value> chain = Future.succeededFuture();
		for (var value : values) {
			chain = chain.compose(v -> storage.putValue(value)
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(value, result));
					}))
			);
		}
		chain.onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(2)
	void testPutPersistentValue(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		Future<Value> chain = Future.succeededFuture();
		for (var value : persistentValues) {
			chain = chain.compose(v -> storage.putValue(value, true)
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(value, result));
					}))
			);
		}
		chain.onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(3)
	void testGetValue(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<Value>>();

		for (var value : values) {
			var future = storage.getValue(value.getId())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(value, result));
					}));
			futures.add(future);
		}

		for (var value : persistentValues) {
			var future = storage.getValue(value.getId())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(value, result));
					}));
			futures.add(future);
		}

		var future = storage.getValue(Id.random())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertNull(result));
				}));
		futures.add(future);

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(4)
	void testGetValues(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		storage.getValues().onComplete(context.succeeding(result -> {
			context.verify(() -> {
				List<Value> expected = new ArrayList<>();
				expected.addAll(values);
				expected.addAll(persistentValues);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				copy.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				expected.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	private static Future<List<Value>> fetchValues(DataStorage storage, int offset, int limit, List<Value> accumulator) {
		return storage.getValues(offset, limit).compose(result -> {
			accumulator.addAll(result);
			if (result.size() < limit) {
				return Future.succeededFuture(accumulator);
			} else {
				return fetchValues(storage, offset + limit, limit, accumulator);
			}
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(5)
	void testGetValuesPaginated(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		List<Value> allValues = new ArrayList<>();
		fetchValues(storage, 0, 8, allValues).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				List<Value> expected = new ArrayList<>();
				expected.addAll(values);
				expected.addAll(persistentValues);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				copy.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				expected.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(6)
	void testGetPersistentValuesUpdatedBefore(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		storage.getValues(true, System.currentTimeMillis()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				var expected = new ArrayList<>(persistentValues);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				copy.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				expected.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	private static Future<List<Value>> fetchValues(DataStorage storage, boolean persistent, long announcedBefore,
															 int offset, int limit, List<Value> accumulator) {
		return storage.getValues(persistent, announcedBefore, offset, limit).compose(result -> {
			accumulator.addAll(result);
			if (result.size() < limit) {
				return Future.succeededFuture(accumulator);
			} else {
				return fetchValues(storage, persistent, announcedBefore, offset + limit, limit, accumulator);
			}
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(7)
	void testGetPersistentValuesUpdatedBeforePaginated(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		List<Value> allValues = new ArrayList<>();
		fetchValues(storage, true, System.currentTimeMillis(), 0, 8, allValues)
				.onComplete(context.succeeding(result -> {
					context.verify(() -> {
						var expected = new ArrayList<>(persistentValues);
						assertEquals(expected.size(), result.size());

						var copy = new ArrayList<>(result);
						copy.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
						expected.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
						assertEquals(expected, copy);
					});
				})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(8)
	void testGetNonPersistentValuesUpdatedBefore(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		storage.getValues(false, System.currentTimeMillis()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				var expected = new ArrayList<>(values);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				copy.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				expected.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(9)
	void testGetNonPersistentValuesUpdatedBeforePaginated(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		List<Value> allValues = new ArrayList<>();
		fetchValues(storage, false, System.currentTimeMillis(), 0, 8, allValues)
				.onComplete(context.succeeding(result -> {
					context.verify(() -> {
						var expected = new ArrayList<>(values);
						assertEquals(expected.size(), result.size());

						var copy = new ArrayList<>(result);
						copy.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
						expected.sort((v1, v2) -> Id.compare(v1.getId(), v2.getId()));
						assertEquals(expected, copy);
					});
				})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(10)
	void testUpdateValue(String name, DataStorage storage, Vertx vertx, VertxTestContext context) throws Exception {
		List<Future<Value>> futures = new ArrayList<>();

		for (int i = 0; i < values.size(); i++) {
			var value = values.get(i);
			final int index = i;

			if (!value.isMutable()) {
				var future = storage.putValue(value).onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(value, result));
				}));
				futures.add(future);
			} else {
				if (!value.hasPrivateKey())
					continue;

				Value updated = value.update(faker.lorem().paragraph().getBytes());
				var future = storage.putValue(updated).andThen(context.succeeding(result -> {
					context.verify(() -> assertEquals(updated, result));
					values.set(index, updated);
				})).compose(v -> {
					Value updated2 = updated.withoutPrivateKey();
					return storage.putValue(updated2).onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(updated, result));
					}));
				}).compose(v -> {
					return storage.getValue(value.getId()).andThen(context.succeeding(result -> {
						// the private key should be kept.
						context.verify(() -> {
							assertTrue(result.hasPrivateKey());
							assertEquals(updated, result);
						});
					}));
				});

				futures.add(future);
			}
		}

		for (int i = 0; i < persistentValues.size(); i++) {
			var value = persistentValues.get(i);
			final int index = i;

			if (!value.isMutable()) {
				var future = storage.putValue(value, true).onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(value, result));
				}));
				futures.add(future);
			} else {
				if (!value.hasPrivateKey())
					continue;

				Value updated = value.update(faker.lorem().paragraph().getBytes());
				var future = storage.putValue(updated, true).andThen(context.succeeding(result -> {
					context.verify(() -> assertEquals(updated, result));
					persistentValues.set(index, updated);
				})).compose(v -> {
					Value updated2 = updated.withoutPrivateKey();
					return storage.putValue(updated2, true).onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(updated, result));
					}));
				}).compose(v -> {
					return storage.getValue(value.getId()).andThen(context.succeeding(result -> {
						// the private key should be kept.
						context.verify(() -> {
							assertTrue(result.hasPrivateKey());
							assertEquals(updated, result);
						});
					}));
				});

				futures.add(future);
			}
		}

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(11)
	void testUpdateValueAnnouncedTime1(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var now = System.currentTimeMillis();
		var futures = new ArrayList<Future<Long>>();

		for (var i = 0; i < values.size() / 2; i++) {
			var value = values.get(i);
			var future = storage.updateValueAnnouncedTime(value.getId())
					.onComplete(context.succeeding(result -> {
						System.out.printf("now: %d, result: %d%n", now, result);
						context.verify(() -> assertTrue(result >= now));
					}));

			futures.add(future);
		}

		for (var i = 0; i < persistentValues.size() / 2; i++) {
			var value = persistentValues.get(i);
			var future = storage.updateValueAnnouncedTime(value.getId())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertTrue(result >= now));
					}));

			futures.add(future);
		}

		var future = storage.updateValueAnnouncedTime(Id.random())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(0L, result));
				}));
		futures.add(future);

		Future.all(futures).onComplete(context.succeeding(unused -> {
			announceTime1 = System.currentTimeMillis();
			context.completeNow();
		}));
	}

	// designed for multiple storages
	private static boolean firstTestUpdateValueAnnouncedTime2Call = true;

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(12)
	@Timeout(value = 40, timeUnit = TimeUnit.SECONDS)
	void testUpdateValueAnnouncedTime2(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		System.out.println("Waiting for 30 seconds to update announced time again...");

		var delay = firstTestUpdateValueAnnouncedTime2Call ? 30000L : 1L;

		vertx.setTimer(delay, tid -> {
			firstTestUpdateValueAnnouncedTime2Call = false;

			var now = System.currentTimeMillis();
			var futures = new ArrayList<Future<Long>>();

			for (var i = values.size() / 2; i < values.size(); i++) {
				var value = values.get(i);
				var future = storage.updateValueAnnouncedTime(value.getId())
						.onComplete(context.succeeding(result -> {
							context.verify(() -> assertTrue(result >= now));
						}));

				futures.add(future);
			}

			for (var i = persistentValues.size() / 2; i < persistentValues.size(); i++) {
				var value = persistentValues.get(i);
				var future = storage.updateValueAnnouncedTime(value.getId())
						.onComplete(context.succeeding(result -> {
							context.verify(() -> assertTrue(result >= now));
						}));

				futures.add(future);
			}

			var future = storage.updateValueAnnouncedTime(Id.random())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(0L, result));
					}));
			futures.add(future);

			Future.all(futures).onComplete(context.succeeding(unused -> {
				announceTime2 = System.currentTimeMillis();
				context.completeNow();
			}));
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(13)
	void testGetValuesAnnouncedBefore(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<List<Value>>>();

		// getValues announced before announceTime1
		var future = storage.getValues(false, announceTime1).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(values.size() / 2, result.size()));
		}));
		futures.add(future);

		future = storage.getValues(true, announceTime1).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(persistentValues.size() / 2, result.size()));
		}));
		futures.add(future);

		// getValues announced before announceTime1 paginated
		future = fetchValues(storage, false, announceTime1, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(values.size() / 2, result.size()));
				}));
		futures.add(future);

		future = fetchValues(storage, true, announceTime1, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(persistentValues.size() / 2, result.size()));
				}));
		futures.add(future);

		// getValues announced before announceTime2
		future = storage.getValues(false, announceTime2).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(values.size(), result.size()));
		}));
		futures.add(future);

		future = storage.getValues(true, announceTime2).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(persistentValues.size(), result.size()));
		}));
		futures.add(future);

		// getValues announced before announceTime2 paginated
		future = fetchValues(storage, false, announceTime2, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(values.size(), result.size()));
				}));
		futures.add(future);

		future = fetchValues(storage, true, announceTime2, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(persistentValues.size(), result.size()));
				}));
		futures.add(future);

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	// designed for multiple storages
	private static boolean firstTestPurgeCall = true;

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(14)
	@Timeout(value = 40, timeUnit = TimeUnit.SECONDS)
	void testPurge(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		System.out.println("Waiting for 30 seconds to purge...");

		var delay = firstTestPurgeCall ? 30000L : 1L;

		vertx.setTimer(delay, tid -> {
			firstTestPurgeCall = false;

			storage.purge().compose(v -> {
				return storage.getValues().onComplete(context.succeeding(result -> {
					var remaining = values.size() - values.size() / 2 + persistentValues.size();
					context.verify(() -> assertEquals(remaining, result.size()));
				}));
			}).onComplete(context.succeedingThenComplete());
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(15)
	void testRemoveValue(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<Boolean>>();

		for (var i = 0; i < persistentValues.size() / 2; i++) {
			var future = storage.removeValue(persistentValues.get(i).getId())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertTrue(result));
					}));
			futures.add(future);
		}

		Future.all(futures).onComplete(context.succeeding(unused -> {
			storage.getValues().onComplete(context.succeeding(result -> {
				var remaining = values.size() - values.size() / 2 + persistentValues.size() - persistentValues.size() / 2;
				context.verify(() -> assertEquals(remaining, result.size()));
			}));
		})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(101)
	void testPutPeer(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		Future<PeerInfo> chain = Future.succeededFuture();
		for (var peerInfo : peerInfos) {
			chain = chain.compose(v -> storage.putPeer(peerInfo)
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(peerInfo, result));
					}))
			);
		}
		chain.onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(102)
	void testPutPersistentPeer(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		Future<PeerInfo> chain = Future.succeededFuture();
		for (var peerInfo : persistentPeerInfos) {
			chain = chain.compose(v -> storage.putPeer(peerInfo, true)
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(peerInfo, result));
					}))
			);
		}
		chain.onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(103)
	void testGetPeer(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<PeerInfo>>();

		for (var peerInfo : peerInfos) {
			var future = storage.getPeer(peerInfo.getId(), peerInfo.getFingerprint())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(peerInfo, result));
					}));
			futures.add(future);
		}

		for (var peerInfo : persistentPeerInfos) {
			var future =  storage.getPeer(peerInfo.getId(), peerInfo.getFingerprint())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(peerInfo, result));
					}));
			futures.add(future);
		}

		var future = storage.getPeer(Id.random(), Random.random().nextLong())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertNull(result));
				}));
		futures.add(future);

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(104)
	void testGetPeersById1(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<List<PeerInfo>>>();

		for (var peerInfo : peerInfos) {
			var future = storage.getPeers(peerInfo.getId()).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					assertEquals(1, result.size());
					assertEquals(peerInfo, result.get(0));
				});
			}));

			futures.add(future);
		}

		for (var peerInfo : persistentPeerInfos) {
			var future = storage.getPeers(peerInfo.getId()).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					assertEquals(1, result.size());
					assertEquals(peerInfo, result.get(0));
				});
			}));

			futures.add(future);
		}

		var future = storage.getPeers(Id.random()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				assertEquals(0, result.size());
			});
		}));

		futures.add(future);

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(105)
	void testGetPeerByIdAndFingerprint1(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<PeerInfo>>();

		for (var peerInfo : peerInfos) {
			var future = storage.getPeer(peerInfo.getId(), peerInfo.getFingerprint()).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					assertNotNull(result);
					assertEquals(peerInfo, result);
				});
			}));

			futures.add(future);
		}

		for (var peerInfo : persistentPeerInfos) {
			var future = storage.getPeer(peerInfo.getId(), peerInfo.getFingerprint()).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					assertNotNull(result);
					assertEquals(peerInfo, result);
				});
			}));

			futures.add(future);
		}

		var future = storage.getPeer(Id.random(), Random.random().nextLong()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				assertNull(result);
			});
		}));

		futures.add(future);

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(106)
	void testGetPeers(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		storage.getPeers().onComplete(context.succeeding(result -> {
			context.verify(() -> {
				List<PeerInfo> expected = new ArrayList<>();
				expected.addAll(peerInfos);
				expected.addAll(persistentPeerInfos);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				var comparator = Comparator.comparing(PeerInfo::getId).thenComparing(PeerInfo::getNodeId);
				copy.sort(comparator);
				expected.sort(comparator);
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	private static Future<List<PeerInfo>> fetchPeers(DataStorage storage, int offset, int limit, List<PeerInfo> accumulator) {
		return storage.getPeers(offset, limit).compose(result -> {
			accumulator.addAll(result);
			if (result.size() < limit) {
				return Future.succeededFuture(accumulator);
			} else {
				return fetchPeers(storage, offset + limit, limit, accumulator);
			}
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(107)
	void testGetPeersPaginated(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		fetchPeers(storage, 0, 8, new ArrayList<>()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				List<PeerInfo> expected = new ArrayList<>();
				expected.addAll(peerInfos);
				expected.addAll(persistentPeerInfos);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				var comparator = Comparator.comparing(PeerInfo::getId).thenComparing(PeerInfo::getNodeId);
				copy.sort(comparator);
				expected.sort(comparator);
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(108)
	void testGetPersistentPeersUpdatedBefore(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		storage.getPeers(true, System.currentTimeMillis()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				var expected = new ArrayList<>(persistentPeerInfos);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				var comparator = Comparator.comparing(PeerInfo::getId).thenComparing(PeerInfo::getNodeId);
				copy.sort(comparator);
				expected.sort(comparator);
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	private static Future<List<PeerInfo>> fetchPeers(DataStorage storage, boolean persistent, long announcedBefore,
												   int offset, int limit, List<PeerInfo> accumulator) {
		return storage.getPeers(persistent, announcedBefore, offset, limit).compose(result -> {
			accumulator.addAll(result);
			if (result.size() < limit) {
				return Future.succeededFuture(accumulator);
			} else {
				return fetchPeers(storage, persistent, announcedBefore, offset + limit, limit, accumulator);
			}
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(109)
	void testGetPersistentPeersUpdatedBeforePaginated(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		fetchPeers(storage, true, System.currentTimeMillis(), 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> {
						var expected = new ArrayList<>(persistentPeerInfos);
						assertEquals(expected.size(), result.size());

						var copy = new ArrayList<>(result);
						var comparator = Comparator.comparing(PeerInfo::getId).thenComparing(PeerInfo::getNodeId);
						copy.sort(comparator);
						expected.sort(comparator);
						assertEquals(expected, copy);
					});
				})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(110)
	void testGetNonPersistentPeersUpdatedBefore(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		storage.getPeers(false, System.currentTimeMillis()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				var expected = new ArrayList<>(peerInfos);
				assertEquals(expected.size(), result.size());

				var copy = new ArrayList<>(result);
				var comparator = Comparator.comparing(PeerInfo::getId).thenComparing(PeerInfo::getNodeId);
				copy.sort(comparator);
				expected.sort(comparator);
				assertEquals(expected, copy);
			});
		})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(111)
	void testGetNonPersistentPeersUpdatedBeforePaginated(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		fetchPeers(storage, false, System.currentTimeMillis(), 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> {
						var expected = new ArrayList<>(peerInfos);
						assertEquals(expected.size(), result.size());

						var copy = new ArrayList<>(result);
						var comparator = Comparator.comparing(PeerInfo::getId).thenComparing(PeerInfo::getNodeId);
						copy.sort(comparator);
						expected.sort(comparator);
						assertEquals(expected, copy);
					});
				})).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(112)
	void testPutPeers(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<List<PeerInfo>>>();

		for (var entry : multiPeers.entrySet()) {
			var infos = entry.getValue();
			var future = storage.putPeers(infos).onComplete(context.succeeding(result -> {
				context.verify(() -> assertEquals(infos, result));
			}));

			futures.add(future);
		}

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(113)
	void testGetPeersById2(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<List<PeerInfo>>>();

		for (var entry : multiPeers.entrySet()) {
			var future = storage.getPeers(entry.getKey()).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					List<PeerInfo> expected = new ArrayList<>(entry.getValue());
					assertEquals(expected.size(), result.size());

					var copy = new ArrayList<>(result);
					var comparator = Comparator.comparing(PeerInfo::getId).thenComparing(PeerInfo::getFingerprint);
					copy.sort(comparator);
					expected.sort(comparator);
					assertEquals(expected, copy);
				});
			}));

			futures.add(future);
		}

		var future = storage.getPeers(Id.random()).onComplete(context.succeeding(result -> {
			context.verify(() -> {
				assertEquals(0, result.size());
			});
		}));

		futures.add(future);

		Future.all(futures).compose(unused -> {
			return storage.getPeers().onComplete(context.succeeding(result -> {
				context.verify(() -> {
					var total = peerInfos.size() + persistentPeerInfos.size() +
							multiPeers.values().stream().mapToInt(List::size).sum();
					assertEquals(total, result.size());
				});
			}));
		}).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(114)
	void testGetPeersByIdAndFingerprint2(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		List<PeerInfo> all = multiPeers.values().stream().flatMap(List::stream).toList();

		var futures = new ArrayList<Future<PeerInfo>>();
		for (var peerInfo : all) {
			var future = storage.getPeer(peerInfo.getId(), peerInfo.getFingerprint()).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					assertNotNull(result);
					assertEquals(peerInfo, result);
				});
			}));

			futures.add(future);
		}

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(115)
	void testRemovePeersById(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<Boolean>>();

		for (var entry : multiPeers.entrySet()) {
			var future = storage.removePeers(entry.getKey()).onComplete(context.succeeding(result -> {
				context.verify(() -> assertTrue(result));
			}));

			futures.add(future);
		}

		var future = storage.removePeers(Id.random()).onComplete(context.succeeding(result -> {
			context.verify(() -> assertFalse(result));
		}));
		futures.add(future);

		Future.all(futures).compose(unused -> {
			return storage.getPeers().onComplete(context.succeeding(result -> {
				context.verify(() -> {
					var total = peerInfos.size() + persistentPeerInfos.size();
					assertEquals(total, result.size());
				});
			}));
		}).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(116)
	void testUpdatePeer(String name, DataStorage storage, Vertx vertx, VertxTestContext context) throws Exception {
		List<Future<PeerInfo>> futures = new ArrayList<>();

		for (int i = 0; i < peerInfos.size(); i++) {
			final var peerInfo = peerInfos.get(i);
			final int index = i;

			if (!peerInfo.hasPrivateKey()) {
				var future = storage.putPeer(peerInfo).onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(peerInfo, result));
				}));
				futures.add(future);
			} else {
				CryptoIdentity node = peerInfo.isAuthenticated() ? nodeIdentities.get(peerInfo.getNodeId()) : null;
				PeerInfo updated = peerInfo.update(node, faker.internet().url());
				var future = storage.putPeer(updated).andThen(context.succeeding(result -> {
					context.verify(() -> assertEquals(updated, result));
					peerInfos.set(index, updated);
				})).compose(v -> {
					PeerInfo updated2 = updated.withoutPrivateKey();
					return storage.putPeer(updated2).onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(updated, result));
					}));
				}).compose(v -> {
					return storage.getPeer(peerInfo.getId(), peerInfo.getFingerprint()).andThen(context.succeeding(result -> {
						// the private key should be kept.
						context.verify(() -> {
							assertTrue(result.hasPrivateKey());
							assertEquals(updated, result);
						});
					}));
				});

				futures.add(future);
			}
		}

		for (int i = 0; i < persistentPeerInfos.size(); i++) {
			final var peerInfo = persistentPeerInfos.get(i);
			final int index = i;

			if (!peerInfo.hasPrivateKey()) {
				var future = storage.putPeer(peerInfo, true).onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(peerInfo, result));
				}));
				futures.add(future);
			} else {
				CryptoIdentity node = peerInfo.isAuthenticated() ? nodeIdentities.get(peerInfo.getNodeId()) : null;
				PeerInfo updated = peerInfo.update(node, faker.internet().url());
				var future = storage.putPeer(updated, true).andThen(context.succeeding(result -> {
					context.verify(() -> assertEquals(updated, result));
					persistentPeerInfos.set(index, updated);
				})).compose(v -> {
					PeerInfo updated2 = updated.withoutPrivateKey();
					return storage.putPeer(updated2, true).onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(updated, result));
					}));
				}).compose(v -> {
					return storage.getPeer(peerInfo.getId(), peerInfo.getFingerprint()).andThen(context.succeeding(result -> {
						// the private key should be kept.
						context.verify(() -> {
							assertTrue(result.hasPrivateKey());
							assertEquals(updated, result);
						});
					}));
				});

				futures.add(future);
			}
		}

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(117)
	void testUpdatePeerAnnouncedTime1(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var now = System.currentTimeMillis();
		var futures = new ArrayList<Future<Long>>();

		for (var i = 0; i < peerInfos.size() / 2; i++) {
			var peerInfo = peerInfos.get(i);
			var future = storage.updatePeerAnnouncedTime(peerInfo.getId(), peerInfo.getFingerprint())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertTrue(result >= now));
					}));

			futures.add(future);
		}

		for (var i = 0; i < persistentPeerInfos.size() / 2; i++) {
			var peerInfo = persistentPeerInfos.get(i);
			var future = storage.updatePeerAnnouncedTime(peerInfo.getId(), peerInfo.getFingerprint())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertTrue(result >= now));
					}));

			futures.add(future);
		}

		var future = storage.updatePeerAnnouncedTime(Id.random(), Random.random().nextLong())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(0L, result));
				}));
		futures.add(future);

		Future.all(futures).onComplete(context.succeeding(unused -> {
			announceTime1 = System.currentTimeMillis();
			context.completeNow();
		}));
	}

	private static boolean firstTestUpdatePeerAnnouncedTime2Call = true;

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(118)
	@Timeout(value = 40, timeUnit = TimeUnit.SECONDS)
	void testUpdatePeerAnnouncedTime2(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		System.out.println("Waiting for 30 seconds to update announced time again...");

		var delay = firstTestUpdatePeerAnnouncedTime2Call ? 30000L : 1L;

		vertx.setTimer(delay, tid -> {
			firstTestUpdatePeerAnnouncedTime2Call = false;
			var now = System.currentTimeMillis();
			var futures = new ArrayList<Future<Long>>();

			for (var i = peerInfos.size() / 2; i < peerInfos.size(); i++) {
				var peerInfo = peerInfos.get(i);
				var future = storage.updatePeerAnnouncedTime(peerInfo.getId(), peerInfo.getFingerprint())
						.onComplete(context.succeeding(result -> {
							context.verify(() -> assertTrue(result >= now));
						}));

				futures.add(future);
			}

			for (var i = persistentPeerInfos.size() / 2; i < persistentPeerInfos.size(); i++) {
				var peerInfo = persistentPeerInfos.get(i);
				var future = storage.updatePeerAnnouncedTime(peerInfo.getId(), peerInfo.getFingerprint())
						.onComplete(context.succeeding(result -> {
							context.verify(() -> assertTrue(result >= now));
						}));

				futures.add(future);
			}

			var future = storage.updatePeerAnnouncedTime(Id.random(), Random.random().nextLong())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(0L, result));
					}));
			futures.add(future);

			Future.all(futures).onComplete(context.succeeding(unused -> {
				announceTime2 = System.currentTimeMillis();
				context.completeNow();
			}));
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(119)
	void testGetPeersAnnouncedBefore(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<List<PeerInfo>>>();

		var future = storage.getPeers(false, announceTime1).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(peerInfos.size() / 2, result.size()));
		}));
		futures.add(future);

		future = storage.getPeers(true, announceTime1).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(persistentPeerInfos.size() / 2, result.size()));
		}));
		futures.add(future);

		future = fetchPeers(storage, false, announceTime1, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(peerInfos.size() / 2, result.size()));
				}));
		futures.add(future);

		future = fetchPeers(storage, true, announceTime1, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(persistentPeerInfos.size() / 2, result.size()));
				}));
		futures.add(future);

		future = storage.getPeers(false, announceTime2).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(peerInfos.size(), result.size()));
		}));
		futures.add(future);

		future = storage.getPeers(true, announceTime2).onComplete(context.succeeding(result -> {
			context.verify(() -> assertEquals(persistentPeerInfos.size(), result.size()));
		}));
		futures.add(future);

		future = fetchPeers(storage, false, announceTime2, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(peerInfos.size(), result.size()));
				}));
		futures.add(future);

		future = fetchPeers(storage, true, announceTime2, 0, 8, new ArrayList<>())
				.onComplete(context.succeeding(result -> {
					context.verify(() -> assertEquals(persistentPeerInfos.size(), result.size()));
				}));
		futures.add(future);

		Future.all(futures).onComplete(context.succeedingThenComplete());
	}

	private static boolean firstTestPurge2Call = true;

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(120)
	@Timeout(value = 40, timeUnit = TimeUnit.SECONDS)
	void testPurge2(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		System.out.println("Waiting for 30 seconds to purge...");

		var delay = firstTestPurge2Call ? 30000L : 1L;

		vertx.setTimer(delay, tid -> {
			firstTestPurge2Call = false;

			storage.purge().compose(v -> {
				var future1 = storage.getValues().onComplete(context.succeeding(result -> {
					var remaining = persistentValues.size() - persistentValues.size() / 2;
					context.verify(() -> assertEquals(remaining, result.size()));
				}));

				var future2 = storage.getPeers().onComplete(context.succeeding(result -> {
					var remaining = peerInfos.size() - peerInfos.size() / 2 + persistentPeerInfos.size();
					context.verify(() -> assertEquals(remaining, result.size()));
				}));

				return Future.all(future1, future2);
			}).onComplete(context.succeedingThenComplete());
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Order(121)
	void testRemovePeer(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		var futures = new ArrayList<Future<Boolean>>();

		for (var i = 0; i < persistentPeerInfos.size() / 2; i++) {
			var peerInfo = persistentPeerInfos.get(i);
			var future = storage.removePeer(peerInfo.getId(), peerInfo.getFingerprint())
					.onComplete(context.succeeding(result -> {
						context.verify(() -> assertTrue(result));
					}));
			futures.add(future);
		}

		Future.all(futures).onComplete(context.succeeding(unused -> {
			storage.getPeers().onComplete(context.succeeding(result -> {
				var remaining = peerInfos.size() - peerInfos.size() / 2 + persistentPeerInfos.size() - persistentPeerInfos.size() / 2;
				context.verify(() -> assertEquals(remaining, result.size()));
			}));
		})).onComplete(context.succeedingThenComplete());
	}


	@ParameterizedTest(name = "{0}")
	@MethodSource("testStoragesProvider")
	@Timeout(value = 1, timeUnit = TimeUnit.MINUTES)
	@Order(122)
	void testGetPeersByIdAndExpectedSequenceNumberWithLimit(String name, DataStorage storage, Vertx vertx, VertxTestContext context) {
		Map<Id, List<PeerInfo>> map = generateMultiPeerInfos(1, 20);

		Id peerId = map.keySet().iterator().next();
		List<PeerInfo> infos = map.get(peerId);
		Future<PeerInfo> chain = Future.succeededFuture();
		for (PeerInfo peerInfo : infos) {
			chain = chain.compose(v -> {
				Promise<PeerInfo> promise = Promise.promise();
				vertx.setTimer(100, (tid) -> {
					storage.putPeer(peerInfo).onComplete(context.succeeding(result -> {
						context.verify(() -> assertEquals(peerInfo, result));
						promise.complete(peerInfo);
					}));
				});

				return promise.future();
			});
		}

		chain.compose(v -> {
			return storage.getPeers(peerId, 0, 1).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					assertEquals(1, result.size());
					assertEquals(infos.get(infos.size() - 1), result.get(0));
				});
			}));
		}).compose(v -> {
			return storage.getPeers(peerId, 0, 5).onComplete(context.succeeding(result -> {
				context.verify(() -> {
					assertEquals(5, result.size());
					List<PeerInfo> expected = infos.subList(infos.size() - 5, infos.size());
					Collections.reverse(expected);
					assertArrayEquals(expected.toArray(), result.toArray());
				});
			}));
		}).onComplete(context.succeedingThenComplete());
	}
}