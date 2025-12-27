package io.bosonnetwork.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;
import io.bosonnetwork.utils.Json;

@ExtendWith(VertxExtension.class)
public class CompactWebTokenAuthTest {
	private static final long DEFAULT_LIFETIME = 10;
	private static final Identity superNodeIdentity = new CryptoIdentity();
	private static final Signature.KeyPair aliceKeyPair = Signature.KeyPair.random();
	private static final Signature.KeyPair iPadKeyPair = Signature.KeyPair.random();
	private static final ClientUser alice = new TestClientUser(Id.of(aliceKeyPair.publicKey().bytes()),
			"Alice", null, null, null);
	private static final ClientDevice iPad = new TestClientDevice(Id.of(iPadKeyPair.publicKey().bytes()), alice.getId(),
			"iPad", "TestCase");

	private static final CompactWebTokenAuth.UserRepository repo = new CompactWebTokenAuth.UserRepository() {
		@Override
		public Future<?> getSubject(Id subject) {
			return subject.equals(alice.getId()) ?
					Future.succeededFuture(alice) : Future.succeededFuture(null);
		}

		@Override
		public Future<?> getAssociated(Id subject, Id associated) {
			return subject.equals(alice.getId()) && associated.equals(iPad.getId()) ?
					Future.succeededFuture(iPad) : Future.succeededFuture(null);
		}
	};

	private static final CompactWebTokenAuth auth = CompactWebTokenAuth.create(superNodeIdentity, repo,
			DEFAULT_LIFETIME, DEFAULT_LIFETIME, 0);

	@Test
	void testSuperNodeIssuedToken(VertxTestContext context) {
		String userToken = auth.generateToken(alice.getId(), "test");
		System.out.println(userToken);
		Future<User> f1 = auth.authenticate(new TokenCredentials(userToken)).andThen(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				Id id = user.get("sub");
				Assertions.assertEquals(alice.getId(), id);
				assertEquals(alice, user.get("user"));
				assertNull(user.get("asc"));
				assertNull(user.get("device"));
				assertEquals("test", user.get("scope"));
				assertEquals(userToken, user.get("access_token"));
				assertFalse(user.expired());
			});
		}));

		String deviceToken = auth.generateToken(alice.getId(), iPad.getId(), "test");
		System.out.println(deviceToken);
		Future<User> f2 = auth.authenticate(new TokenCredentials(deviceToken)).andThen(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				Assertions.assertEquals(alice.getId(), user.get("sub"));
				assertEquals(alice, user.get("user"));
				Assertions.assertEquals(iPad.getId(), user.get("asc"));
				assertEquals(iPad, user.get("device"));
				assertEquals("test", user.get("scope"));
				assertEquals(deviceToken, user.get("access_token"));
				assertFalse(user.expired());
			});
		}));

		Future.all(f1, f2).andThen(context.succeedingThenComplete());
	}

	@Test
	void testSuperNodeIssuedAndExpiredToken(Vertx vertx, VertxTestContext context) {
		String userToken = auth.generateToken(alice.getId(), "test");
		System.out.println(userToken);
		String deviceToken = auth.generateToken(alice.getId(), iPad.getId(), "test");
		System.out.println(deviceToken);

		Promise<Void> promise = Promise.promise();
		vertx.setTimer(10000, l -> promise.complete());
		System.out.println("Waiting for token expiration...");
		promise.future().compose(v -> {
			Future<User> f1 = auth.authenticate(new TokenCredentials(userToken)).andThen(ar -> {
				context.verify(() -> {
					assertTrue(ar.failed());
					assertTrue(ar.cause().getMessage().contains("expired"));
				});
			}).otherwiseEmpty();

			Future<User> f2 = auth.authenticate(new TokenCredentials(deviceToken)).andThen(ar -> {
				context.verify(() -> {
					assertTrue(ar.failed());
					assertTrue(ar.cause().getMessage().contains("expired"));
				});
			}).otherwiseEmpty();

			return Future.all(f1, f2);
		}).andThen(context.succeedingThenComplete());
	}

	@Test
	void testSuperNodeIssuedAndInvalidSigToken(Vertx vertx, VertxTestContext context) {
		String userToken = auth.generateToken(alice.getId(), "test");
		System.out.println(userToken);
		byte[] sig = Json.BASE64_DECODER.decode(userToken.substring(userToken.lastIndexOf('.') + 1));
		sig[0] = (byte) ~sig[0];
		String invalidUserToken = userToken.substring(0, userToken.lastIndexOf('.')) + '.' + Json.BASE64_ENCODER.encodeToString(sig);

		String deviceToken = auth.generateToken(alice.getId(), iPad.getId(), "test");
		System.out.println(deviceToken);
		sig = Json.BASE64_DECODER.decode(deviceToken.substring(deviceToken.lastIndexOf('.') + 1));
		sig[0] = (byte) ~sig[0];
		String invalidDeviceToken = deviceToken.substring(0, deviceToken.lastIndexOf('.')) + '.' + Json.BASE64_ENCODER.encodeToString(sig);

		Future<User> f1 = auth.authenticate(new TokenCredentials(invalidUserToken)).andThen(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("signature verification failed"));
			});
		}).otherwiseEmpty();

		Future<User> f2 = auth.authenticate(new TokenCredentials(invalidDeviceToken)).andThen(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("signature verification failed"));
			});
		}).otherwiseEmpty();

		Future.all(f1, f2).andThen(context.succeedingThenComplete());
	}
	
	@Test
	void testInvalidBase64Token(VertxTestContext context) {
		String invalidToken = "invalid-payload.invalid-signature";
		auth.authenticate(new TokenCredentials(invalidToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("format error"));
				context.completeNow();
			});
		});
	}

	private String generateClientToken(Signature.KeyPair signer, Id subject, Id associated, Id audience, long expiration, String scope) throws Exception {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("jti", Random.randomBytes(24));
		claims.put("sub", subject.bytes());
		if (associated != null)
			claims.put("asc", associated.bytes());

		// Client issued token MUST have 'iss' (issuer) and 'aud' (audience)
		claims.put("iss", signer.publicKey().bytes());
		if (audience != null)
			claims.put("aud", audience.bytes());

		if (scope != null)
			claims.put("scp", scope);

		long now = System.currentTimeMillis() / 1000;
		if (expiration <= 0)
			expiration = now + DEFAULT_LIFETIME;

		claims.put("exp", expiration);

		byte[] payload = Json.cborMapper().writeValueAsBytes(claims);
		byte[] sig = signer.privateKey().sign(payload);

		return Json.BASE64_ENCODER.encodeToString(payload) + "." + Json.BASE64_ENCODER.encodeToString(sig);
	}

	@Test
	void testClientIssuedToken(VertxTestContext context) throws Exception {
		String userToken = generateClientToken(aliceKeyPair, alice.getId(), null, superNodeIdentity.getId(), 0, "test");
		System.out.println("ClientToken: " + userToken);

		auth.authenticate(new TokenCredentials(userToken)).onComplete(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				assertEquals(alice, user.get("user"));
				assertNull(user.get("asc"));
				assertEquals("test", user.get("scope"));
				assertFalse(user.expired());
				context.completeNow();
			});
		}));
	}

	@Test
	void testDeviceIssuedToken(VertxTestContext context) throws Exception {
		String deviceToken = generateClientToken(iPadKeyPair, alice.getId(), iPad.getId(), superNodeIdentity.getId(), 0, "test");
		System.out.println("DeviceToken: " + deviceToken);

		auth.authenticate(new TokenCredentials(deviceToken)).onComplete(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				Assertions.assertEquals(iPad.getId(), user.get("asc"));
				assertEquals(iPad, user.get("device"));
				assertFalse(user.expired());
				context.completeNow();
			});
		}));
	}

	@Test
	void testClientIssuedTokenWrongAudience(VertxTestContext context) throws Exception {
		// Wrong audience (random ID)
		String userToken = generateClientToken(aliceKeyPair, alice.getId(), null, Id.random(), 0, "test");

		auth.authenticate(new TokenCredentials(userToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("wrong audience"));
				context.completeNow();
			});
		});
	}
}