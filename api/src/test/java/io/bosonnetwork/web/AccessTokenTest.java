package io.bosonnetwork.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.vertx.core.Future;
import io.vertx.ext.auth.authentication.TokenCredentials;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;

@ExtendWith(VertxExtension.class)
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

	@Test
	void testGenerateUserToken(VertxTestContext context) {
		AccessToken accessToken = new AccessToken(aliceIdentity);
		Id audience = superNodeIdentity.getId();
		
		String token = accessToken.generate(audience, "read write", 60);
		System.out.println("Generated Token: " + token);
		
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
		
		String token = accessToken.generate(alice.getId(), audience, "read write", 60);
		
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
}