package io.bosonnetwork.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.authentication.TokenCredentials;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;

@ExtendWith(VertxExtension.class)
@SuppressWarnings("CodeBlock2Expr")
public class AccessTokenTest {
	private static final Identity superNodeIdentity = new CryptoIdentity();
	private static final Identity aliceIdentity = new CryptoIdentity();
	private static final Identity iPadIdentity = new CryptoIdentity();
    private static final ClientUser alice = new TestClientUser(aliceIdentity.getId(), "Alice", null, null, null);
    private static final ClientDevice iPad = new TestClientDevice(iPadIdentity.getId(), alice.getId(), "iPad", "TestCase");
	
	// Mock repo for authentication verification
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
	
	private static final CompactWebTokenAuth auth = CompactWebTokenAuth.create(superNodeIdentity, repo, 3600, 3600, 0);

	private static void printToken(String token) {
		System.out.println("Token: " + token);
		String[] parts = token.split("\\.");
		String payload = Json.toString(Json.parse(Json.BASE64_DECODER.decode(parts[0])));
		System.out.println(" - " + payload);
		System.out.println(" - " + parts[1]);
	}

	@Test
	void testGenerateUserToken(VertxTestContext context) {
		AccessToken accessToken = new AccessToken(aliceIdentity);
		Id audience = superNodeIdentity.getId();
		
		String token = accessToken.generate(audience, "read write");
		printToken(token);
		
		// Verify using CompactWebTokenAuth
		auth.authenticate(new TokenCredentials(token)).onComplete(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				Assertions.assertEquals(alice.getId(), user.get("sub"));
				assertEquals("read write", user.get("scope"));
				// 'aud' is checked implicitly by successful authentication for non-server-issued tokens
				context.completeNow();
			});
		}));
	}

	@Test
	void testGenerateDeviceToken(VertxTestContext context) {
		AccessToken accessToken = new AccessToken(iPadIdentity);
		Id audience = superNodeIdentity.getId();
		
		String token = accessToken.generate(alice.getId(), audience, "read write");
		printToken(token);
		
		auth.authenticate(new TokenCredentials(token)).onComplete(context.succeeding(user -> {
			context.verify(() -> {
				assertNotNull(user);
				Assertions.assertEquals(alice.getId().toString(), user.subject());
				Assertions.assertEquals(alice.getId(), user.get("sub"));
				Assertions.assertEquals(iPad.getId(), user.get("asc"));
				assertEquals("read write", user.get("scope"));
				context.completeNow();
			});
		}));
	}

	@Test
	void testGenerateUserTokenWithWrongAudience(VertxTestContext context) {
		AccessToken accessToken = new AccessToken(aliceIdentity);
		Id audience = Id.random();

		String token = accessToken.generate(audience, "read write");
		printToken(token);

		// Verify using CompactWebTokenAuth
		auth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("wrong audience"));
				context.completeNow();
			});
		});
	}

	@Test
	void testGenerateDeviceTokenWithWrongAudience(VertxTestContext context) {
		AccessToken accessToken = new AccessToken(iPadIdentity);
		Id audience = Id.random();

		String token = accessToken.generate(alice.getId(), audience, "read write");
		printToken(token);

		auth.authenticate(new TokenCredentials(token)).onComplete(ar -> {
			context.verify(() -> {
				assertTrue(ar.failed());
				assertTrue(ar.cause().getMessage().contains("wrong audience"));
				context.completeNow();
			});
		});
	}

	@Test
	void testExpiredUserAndDeviceToken(Vertx vertx, VertxTestContext context) {
		Id audience = superNodeIdentity.getId();

		String userToken = new AccessToken(aliceIdentity, 10).generate(audience, "read write");
		printToken(userToken);

		String deviceToken = new AccessToken(iPadIdentity, 10).generate(alice.getId(), audience, "read write");
		printToken(deviceToken);

		Promise<Void> promise = Promise.promise();
		vertx.setTimer(10000, l -> promise.complete());
		System.out.println("Waiting for token expiration...");

		promise.future().compose(v -> {
			Future<Void> f1 = auth.authenticate(new TokenCredentials(userToken)).onComplete(ar -> {
				context.verify(() -> {
					assertTrue(ar.failed());
					assertTrue(ar.cause().getMessage().contains("expired"));
				});
			}).mapEmpty();

			Future<Void> f2 = auth.authenticate(new TokenCredentials(deviceToken)).onComplete(ar -> {
				context.verify(() -> {
					assertTrue(ar.failed());
					assertTrue(ar.cause().getMessage().contains("expired"));
				});
			}).mapEmpty();

			return Future.join(f1, f2).otherwiseEmpty();
		}).onComplete(context.succeedingThenComplete());
	}

	@Test
	void testGenerateUserTokenWithWrongSubject() {
		AccessToken accessToken = new AccessToken(aliceIdentity);
		Id audience = superNodeIdentity.getId();

		Exception e = assertThrows(IllegalArgumentException.class, () -> {
			accessToken.generate(Id.random(), null, audience, "read write", 60);
		});
		assertTrue(e.getMessage().contains("subject must be the issuer ID"));
	}

	@Test
	void testGenerateDeviceTokenWithWrongSubject() {
		AccessToken accessToken = new AccessToken(iPadIdentity);
		Id audience = superNodeIdentity.getId();

		Exception e = assertThrows(IllegalArgumentException.class, () -> {
			accessToken.generate(alice.getId(), Id.random(), audience, "read write", 60);
		});
		assertTrue(e.getMessage().contains("associated must be the issuer ID"));
	}

	@Test
	void testGenerateUserTokenWithoutAudience() {
		AccessToken accessToken = new AccessToken(aliceIdentity);

		Exception e = assertThrows(NullPointerException.class, () -> {
			accessToken.generate(null, "read write");
		});
		assertTrue(e.getMessage().contains("audience cannot be null"));
	}

	@Test
	void testGenerateDeviceTokenWithoutAudience() {
		AccessToken accessToken = new AccessToken(iPadIdentity);

		Exception e = assertThrows(NullPointerException.class, () -> {
			accessToken.generate(alice.getId(), iPad.getId(), null, "read write", 60);
		});
		assertTrue(e.getMessage().contains("audience cannot be null"));
	}
}