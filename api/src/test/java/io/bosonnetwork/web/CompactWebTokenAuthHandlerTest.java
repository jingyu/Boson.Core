package io.bosonnetwork.web;

import java.util.Base64;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.service.ClientUser;

@ExtendWith(VertxExtension.class)
public class CompactWebTokenAuthHandlerTest {
	private static final Identity superNodeIdentity = new CryptoIdentity();
	private static final Signature.KeyPair aliceKeyPair = Signature.KeyPair.random();
	private static final ClientUser alice = new TestClientUser(Id.of(aliceKeyPair.publicKey().bytes()),
			"Alice", null, null, null);

	private static final io.bosonnetwork.web.CompactWebTokenAuth.UserRepository repo = new io.bosonnetwork.web.CompactWebTokenAuth.UserRepository() {
		@Override
		public Future<?> getSubject(Id subject) {
			return subject.equals(alice.getId()) ?
					Future.succeededFuture(alice) : Future.succeededFuture(null);
		}

		@Override
		public Future<?> getAssociated(Id subject, Id associated) {
			return Future.succeededFuture(null);
		}
	};

	private static final io.bosonnetwork.web.CompactWebTokenAuth auth = io.bosonnetwork.web.CompactWebTokenAuth.create(superNodeIdentity, repo,
			3600, 3600, 0);

	@Test
	void testHandlerSuccess(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.route().handler(BodyHandler.create());
		
		// Protected route requiring "read" scope
		router.get("/protected")
			.handler(io.bosonnetwork.web.CompactWebTokenAuthHandler.create(auth).withScope("read"))
			.handler(ctx -> ctx.response().end(new JsonObject().put("status", "ok").encode()));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				String token = auth.generateToken(alice.getId(), "read");
				
				client.get(port, "localhost", "/protected")
					.bearerTokenAuthentication(token)
					.send()
					.onComplete(context.succeeding(resp -> {
						context.verify(() -> {
							Assertions.assertEquals(200, resp.statusCode());
							Assertions.assertEquals("ok", resp.bodyAsJsonObject().getString("status"));
						});
						server.close().onComplete(context.succeedingThenComplete());
					}));
			}));
	}

	@Test
	void testHandlerMissingScope(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.get("/protected")
			.handler(io.bosonnetwork.web.CompactWebTokenAuthHandler.create(auth).withScope("admin"))
			.handler(ctx -> ctx.response().end("ok"));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				// Token only has "read" scope
				String token = auth.generateToken(alice.getId(), "read");

				client.get(port, "localhost", "/protected")
					.bearerTokenAuthentication(token)
					.send()
					.onComplete(context.succeeding(resp -> {
						context.verify(() -> {
							Assertions.assertEquals(403, resp.statusCode());
						});
						server.close().onComplete(context.succeedingThenComplete());
					}));
			}));
	}
	
	@Test
	void testHandlerWithPadding(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.get("/protected")
			.handler(io.bosonnetwork.web.CompactWebTokenAuthHandler.create(auth))
			.handler(ctx -> ctx.response().end("ok"));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				// Generate token
				String token = auth.generateToken(alice.getId());
				String[] parts = token.split("\\.", 2);
				String paddingToken = Base64.getUrlEncoder().encodeToString(Base64.getUrlDecoder().decode(parts[0])) + '.' +
						Base64.getUrlEncoder().encodeToString(Base64.getUrlDecoder().decode(parts[1]));
				 
				client.get(port, "localhost", "/protected")
					.bearerTokenAuthentication(paddingToken)
					.send()
					.onComplete(context.succeeding(resp -> {
						context.verify(() -> {
							Assertions.assertEquals(400, resp.statusCode());
						});
						server.close().onComplete(context.succeedingThenComplete());
					}));
			}));
	}
}