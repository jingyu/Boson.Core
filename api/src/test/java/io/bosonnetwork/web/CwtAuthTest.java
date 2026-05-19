package io.bosonnetwork.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

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
import io.bosonnetwork.cwt.InvalidCborTagException;
import io.bosonnetwork.cwt.InvalidClaimException;
import io.bosonnetwork.cwt.InvalidSignatureException;
import io.bosonnetwork.cwt.SignedCwt;
import io.bosonnetwork.cwt.TokenExpiredException;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;

@ExtendWith(VertxExtension.class)
@SuppressWarnings("CodeBlock2Expr")
public class CwtAuthTest {
	private static final int DEFAULT_LIFETIME = 10; // 10 seconds
	private static final Identity superNodeIdentity = new CryptoIdentity();
	private static final Identity aliceIdentity = new CryptoIdentity();
	private static final Identity iPadIdentity = new CryptoIdentity();
	private static final ClientUser alice = new TestClientUser(aliceIdentity.getId(),
			"Alice", null, null, null);
	private static final ClientDevice iPad = new TestClientDevice(iPadIdentity.getId(), alice.getId(),
			"iPad", "TestCase");

	static CwtAuthOptions options = new CwtAuthOptions()
			.setIdentity(superNodeIdentity)
			.setExpectedAudience(superNodeIdentity.getId())
			.setDefaultTtl(DEFAULT_LIFETIME)
			.setLeeway(0)
			.setClientProvider(new ClientProvider() {
				@Override
				public Future<?> getUser(Id userId) {
					return userId.equals(alice.getId()) ?
							Future.succeededFuture(alice) : Future.succeededFuture(null);
				}

				@Override
				public Future<?> getClient(Id userId, Id clientId) {
					return userId.equals(alice.getId()) && clientId.equals(iPad.getId()) ?
							Future.succeededFuture(iPad) : Future.succeededFuture(null);
				}
			});

	private static final CwtAuth auth = CwtAuth.create(options);

	private static void printToken(String token) {
		try {
			SignedCwt cwt = SignedCwt.parse(Json.BASE64_DECODER.decode(token));
			System.out.println("Token: " + cwt.toString());
		} catch (Exception e) {
			System.out.println("Token error: " + e.getMessage());
		}
	}

	@Test
	void testSuperNodeIssuedToken(VertxTestContext context) {
		String userToken = auth.generateToken(alice.getId(), "test");
		printToken(userToken);
		Future<User> f1 = auth.authenticate(new TokenCredentials(userToken)).andThen(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				Id id = user.get("sub");
				assertEquals(alice.getId(), id);
				assertEquals(alice, user.get("client"));
				assertEquals(alice.getId(), user.get("userId"));
				assertNull(user.get("clientId"));
				assertEquals("test", user.get("scope"));
				assertEquals(userToken, user.get("access_token"));
				assertFalse(user.expired());
			});
		}));

		String deviceToken = auth.generateToken(alice.getId(), iPad.getId(), "test");
		printToken(deviceToken);
		Future<User> f2 = auth.authenticate(new TokenCredentials(deviceToken)).andThen(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				assertEquals(alice.getId().toString(), user.subject());
				assertEquals(alice.getId(), user.get("sub"));
				assertEquals(iPad, user.get("client"));
				assertEquals(alice.getId(), user.get("userId"));
				assertEquals(iPad.getId(), user.get("clientId"));
				assertEquals("test", user.get("scope"));
				assertEquals(deviceToken, user.get("access_token"));
				assertFalse(user.expired());
			});
		}));

		Future.all(f1, f2).andThen(context.succeedingThenComplete());
	}

	@Test
	void testSuperNodeIssuedExpiredToken(Vertx vertx, VertxTestContext context) {
		String userToken = auth.generateToken(alice.getId(), null,"test", 1);
		printToken(userToken);
		String deviceToken = auth.generateToken(alice.getId(), iPad.getId(), "test", 1);
		printToken(deviceToken);

		Promise<Void> promise = Promise.promise();
		vertx.setTimer(2000, l -> promise.complete());
		System.out.println("Waiting for token expiration...");
		promise.future().compose(v -> {
			Future<User> f1 = auth.authenticate(new TokenCredentials(userToken)).onComplete(ar -> {
				context.verify(() -> {
					assertTrue(ar.failed());
					assertInstanceOf(TokenExpiredException.class, ar.cause());
				});
			});

			Future<User> f2 = auth.authenticate(new TokenCredentials(deviceToken)).onComplete(ar -> {
				context.verify(() -> {
					assertTrue(ar.failed());
					assertInstanceOf(TokenExpiredException.class, ar.cause());
				});
			});

			return Future.all(f1, f2).otherwiseEmpty();
		}).andThen(context.succeedingThenComplete());
	}

	@Test
	void testSuperNodeIssuedInvalidSigToken(VertxTestContext context) {
		String userToken = auth.generateToken(alice.getId(), "test");
		printToken(userToken);
		byte[] token = Json.BASE64_DECODER.decode(userToken);
		token[token.length - 8] = (byte) ~token[token.length - 8];
		String invalidUserToken = Json.BASE64_ENCODER.encodeToString(token);

		String deviceToken = auth.generateToken(alice.getId(), iPad.getId(), "test");
		printToken(deviceToken);
		token = Json.BASE64_DECODER.decode(deviceToken);
		token[token.length - 8] = (byte) ~token[token.length - 8];
		String invalidDeviceToken = Json.BASE64_ENCODER.encodeToString(token);

		Future<User> f1 = auth.authenticate(new TokenCredentials(invalidUserToken)).andThen(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(InvalidSignatureException.class, ar.cause());
			});
		}).otherwiseEmpty();

		Future<User> f2 = auth.authenticate(new TokenCredentials(invalidDeviceToken)).andThen(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(InvalidSignatureException.class, ar.cause());
			});
		}).otherwiseEmpty();

		Future.all(f1, f2).andThen(context.succeedingThenComplete());
	}
	
	@Test
	void testInvalidBase64Token(VertxTestContext context) {
		String invalidToken = Json.BASE64_ENCODER.encodeToString("invalid-payload.invalid-signature".getBytes());
		auth.authenticate(new TokenCredentials(invalidToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(InvalidCborTagException.class, ar.cause());
				context.completeNow();
			});
		});
	}

	private String generateClientToken(Identity issuer, Id userId, Id clientId, Id audience, int expiration, String scope) throws Exception {
		SignedCwt.Builder builder = SignedCwt.builder(issuer)
				.subject(userId);

		if (audience != null)
			builder.audience(audience);

		builder.expiration(Duration.ofSeconds(expiration == 0 ? DEFAULT_LIFETIME : expiration));

		builder.tokenId(Random.randomBytes(8));

		if (scope != null && !scope.isEmpty())
			builder.scope(scope);

		if (clientId != null)
			builder.clientId(clientId);

		return builder.buildBase64();
	}

	@Test
	void testClientIssuedToken(VertxTestContext context) throws Exception {
		String userToken = generateClientToken(aliceIdentity, alice.getId(), null, superNodeIdentity.getId(), 0, "test");
		printToken(userToken);

		auth.authenticate(new TokenCredentials(userToken)).onComplete(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				assertEquals(alice, user.get("client"));
				assertNull(user.get("clientId"));
				assertEquals("test", user.get("scope"));
				assertFalse(user.expired());
				context.completeNow();
			});
		}));
	}

	@Test
	void testDeviceIssuedToken(VertxTestContext context) throws Exception {
		String deviceToken = generateClientToken(iPadIdentity, alice.getId(), iPad.getId(), superNodeIdentity.getId(), 0, "test");
		printToken(deviceToken);

		auth.authenticate(new TokenCredentials(deviceToken)).onComplete(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				Assertions.assertEquals(iPad.getId(), user.get("clientId"));
				assertEquals(iPad, user.get("client"));
				assertFalse(user.expired());
				context.completeNow();
			});
		}));
	}

	@Test
	void testClientIssuedTokenWrongAudience(VertxTestContext context) throws Exception {
		// Wrong audience (random ID)
		String userToken = generateClientToken(aliceIdentity, alice.getId(), null, Id.random(), 0, "test");

		auth.authenticate(new TokenCredentials(userToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(InvalidClaimException.class, ar.cause());
				context.completeNow();
			});
		});

		// Wrong audience (random ID)
		String deviceToken = generateClientToken(iPadIdentity, alice.getId(), iPad.getId(), Id.random(), 0, "test");

		auth.authenticate(new TokenCredentials(deviceToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(InvalidClaimException.class, ar.cause());
				context.completeNow();
			});
		});
	}

	@Test
	void testClientIssuedTokenWithoutAudience(VertxTestContext context) throws Exception {
		String userToken = generateClientToken(aliceIdentity, alice.getId(), null, null, 0, "test");

		auth.authenticate(new TokenCredentials(userToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(InvalidClaimException.class, ar.cause());
				context.completeNow();
			});
		});

		String deviceToken = generateClientToken(iPadIdentity, alice.getId(), iPad.getId(), null, 0, "test");

		auth.authenticate(new TokenCredentials(deviceToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(InvalidClaimException.class, ar.cause());
				context.completeNow();
			});
		});
	}

	@Test
	void testClientIssuedTokenWrongIssuer(VertxTestContext context) throws Exception {
		// Wrong issuer
		String userToken = generateClientToken(aliceIdentity, Id.random(), null, superNodeIdentity.getId(), 0, "test");

		auth.authenticate(new TokenCredentials(userToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("unacceptable issuer"));
				context.completeNow();
			});
		});

		String deviceToken = generateClientToken(iPadIdentity, alice.getId(), Id.random(), superNodeIdentity.getId(), 0, "test");

		auth.authenticate(new TokenCredentials(deviceToken)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("unacceptable issuer"));
				context.completeNow();
			});
		});
	}

	@Test
	void testGenerateTokenValidation() {
		// Null user ID
		assertThrows(NullPointerException.class, () -> auth.generateToken(null, "test"));
		// Negative TTL
		assertThrows(IllegalArgumentException.class, () -> auth.generateToken(alice.getId(), null, "test", -1));
	}

	@Test
	void testUserNotExists(VertxTestContext context) throws Exception {
		// User self-issued valid token, but user does not exist in provider
		Identity randomUserIdentity = new CryptoIdentity();
		String token = generateClientToken(randomUserIdentity, randomUserIdentity.getId(), null, superNodeIdentity.getId(), 0, "test");
		
		auth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("user not exists"));
				context.completeNow();
			});
		});
	}

	@Test
	void testClientNotExists(VertxTestContext context) throws Exception {
		// Client self-issued valid token, but client does not exist in provider
		Identity randomDeviceIdentity = new CryptoIdentity();
		String token = generateClientToken(randomDeviceIdentity, alice.getId(), randomDeviceIdentity.getId(), superNodeIdentity.getId(), 0, "test");

		auth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("client not exists"));
				context.completeNow();
			});
		});
	}

	@Test
	void testMissingSubjectClaim(VertxTestContext context) throws Exception {
		// Build token with missing subject
		byte[] tokenBytes = SignedCwt.builder(superNodeIdentity)
				.audience(superNodeIdentity.getId())
				.build();
		String token = Json.BASE64_ENCODER.encodeToString(tokenBytes);

		auth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("missing subject"));
				context.completeNow();
			});
		});
	}

	@Test
	void testInvalidSubjectClaim(VertxTestContext context) throws Exception {
		// Build token with invalid type for subject
		byte[] tokenBytes = SignedCwt.builder(superNodeIdentity)
				.claim(io.bosonnetwork.cwt.Claim.SUBJECT.getValue(), 123)
				.audience(superNodeIdentity.getId())
				.build();
		String token = Json.BASE64_ENCODER.encodeToString(tokenBytes);

		auth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("invalid subject"));
				context.completeNow();
			});
		});
	}

	@Test
	void testInvalidClientIdClaim(VertxTestContext context) throws Exception {
		// Build token with invalid type for client_id
		byte[] tokenBytes = SignedCwt.builder(superNodeIdentity)
				.subject(alice.getId())
				.claim(io.bosonnetwork.cwt.Claim.CLIENT_ID.getValue(), 456)
				.audience(superNodeIdentity.getId())
				.build();
		String token = Json.BASE64_ENCODER.encodeToString(tokenBytes);

		auth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("invalid client_id"));
				context.completeNow();
			});
		});
	}

	@Test
	void testClientProviderFailedFuture(VertxTestContext context) throws Exception {
		CwtAuthOptions failOptions = new CwtAuthOptions()
				.setIdentity(superNodeIdentity)
				.setExpectedAudience(superNodeIdentity.getId())
				.setClientProvider(new ClientProvider() {
					@Override
					public Future<?> getUser(Id userId) {
						return Future.failedFuture("Database connection failed");
					}

					@Override
					public Future<?> getClient(Id userId, Id clientId) {
						return Future.failedFuture("Database connection failed");
					}
				});
		CwtAuth failAuth = CwtAuth.create(failOptions);
		String token = failAuth.generateToken(alice.getId(), "test");

		failAuth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertEquals("Database connection failed", ar.cause().getMessage());
				context.completeNow();
			});
		});
	}

	@Test
	void testInvalidCredentialsType(VertxTestContext context) {
		auth.authenticate(new io.vertx.ext.auth.authentication.UsernamePasswordCredentials("user", "pass")).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertInstanceOf(io.vertx.ext.auth.authentication.CredentialValidationException.class, ar.cause());
				context.completeNow();
			});
		});
	}
}