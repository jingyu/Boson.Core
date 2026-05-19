package io.bosonnetwork.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
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
public class CwtAuthHandlerTest {
	private static final Identity superNodeIdentity = new CryptoIdentity();
	private static final Signature.KeyPair aliceKeyPair = Signature.KeyPair.random();
	private static final ClientUser alice = new TestClientUser(Id.of(aliceKeyPair.publicKey().bytes()),
			"Alice", null, null, null);

	static CwtAuthOptions options = new CwtAuthOptions()
			.setIdentity(superNodeIdentity)
			.setExpectedAudience(superNodeIdentity.getId())
			.setDefaultTtl(3600)
			.setLeeway(0)
			.setClientProvider(new ClientProvider() {
				@Override
				public Future<?> getUser(Id userId) {
					return userId.equals(alice.getId()) ?
							Future.succeededFuture(alice) : Future.succeededFuture(null);
				}

				@Override
				public Future<?> getClient(Id userId, Id clientId) {
					return Future.succeededFuture(null);
				}
			});

	private static final CwtAuth auth = CwtAuth.create(options);

	@Test
	void testHandlerSuccess(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.route().handler(BodyHandler.create());
		
		// Protected route requiring "read" scope
		router.get("/protected")
			.handler(CwtAuthHandler.create(auth).withScope("read"))
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
			.handler(CwtAuthHandler.create(auth).withScope("admin"))
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
	void testHandlerWithoutScope(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.get("/protected")
			.handler(CwtAuthHandler.create(auth).withScope("admin"))
			.handler(ctx -> ctx.response().end("ok"));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				// Generate token without scope
				String token = auth.generateToken(alice.getId(), null);

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
	void testHandlerMultipleScopes(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.get("/protected")
			.handler(CwtAuthHandler.create(auth).withScopes(java.util.List.of("read", "write")))
			.handler(ctx -> ctx.response().end("ok"));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				String token = auth.generateToken(alice.getId(), "read write");

				client.get(port, "localhost", "/protected")
					.bearerTokenAuthentication(token)
					.send()
					.onComplete(context.succeeding(resp -> {
						context.verify(() -> {
							Assertions.assertEquals(200, resp.statusCode());
						});
						server.close().onComplete(context.succeedingThenComplete());
					}));
			}));
	}

	@Test
	void testHandlerCustomScopeDelimiter(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.get("/protected")
			.handler(CwtAuthHandler.create(auth)
				.scopeDelimiter(",")
				.withScopes(java.util.List.of("read", "write")))
			.handler(ctx -> ctx.response().end("ok"));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				// Comma delimited scope string
				String token = auth.generateToken(alice.getId(), "read,write");

				client.get(port, "localhost", "/protected")
					.bearerTokenAuthentication(token)
					.send()
					.onComplete(context.succeeding(resp -> {
						context.verify(() -> {
							Assertions.assertEquals(200, resp.statusCode());
						});
						server.close().onComplete(context.succeedingThenComplete());
					}));
			}));
	}

	@Test
	void testHandlerMetadataScopes(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		
		// 1. Metadata with String scope
		Route route1 = router.get("/meta-string")
			.handler(CwtAuthHandler.create(auth));
		route1.putMetadata("scopes", "read");
		route1.handler(ctx -> ctx.response().end("ok"));

		// 2. Metadata with List scope
		Route route2 = router.get("/meta-list")
			.handler(CwtAuthHandler.create(auth));
		route2.putMetadata("scopes", java.util.List.of("read", "write"));
		route2.handler(ctx -> ctx.response().end("ok"));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				String token = auth.generateToken(alice.getId(), "read write");

				// Test String metadata scope (should succeed)
				client.get(port, "localhost", "/meta-string")
					.bearerTokenAuthentication(token)
					.send()
					.compose(resp -> {
						context.verify(() -> {
							Assertions.assertEquals(200, resp.statusCode());
						});
						// Test List metadata scope (should succeed)
						return client.get(port, "localhost", "/meta-list")
							.bearerTokenAuthentication(token)
							.send();
					})
					.onComplete(context.succeeding(resp2 -> {
						context.verify(() -> {
							Assertions.assertEquals(200, resp2.statusCode());
						});
						server.close().onComplete(context.succeedingThenComplete());
					}));
			}));
	}

	@Test
	void testHandlerAuthenticationFailure(Vertx vertx, VertxTestContext context) {
		Router router = Router.router(vertx);
		router.get("/protected")
			.handler(CwtAuthHandler.create(auth))
			.handler(ctx -> ctx.response().end("ok"));

		vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.onComplete(context.succeeding(server -> {
				int port = server.actualPort();
				WebClient client = WebClient.create(vertx);

				// Expired token
				String token = auth.generateToken(alice.getId(), null, "read", 1);

				// Wait 2.5 seconds for token expiration to ensure strictly expired
				vertx.setTimer(2500, id -> {
					client.get(port, "localhost", "/protected")
						.bearerTokenAuthentication(token)
						.send()
						.onComplete(context.succeeding(resp -> {
							context.verify(() -> {
								// Should be 401 Unauthorized for expired tokens
								Assertions.assertEquals(401, resp.statusCode());
							});
							server.close().onComplete(context.succeedingThenComplete());
						}));
				});
			}));
	}
}