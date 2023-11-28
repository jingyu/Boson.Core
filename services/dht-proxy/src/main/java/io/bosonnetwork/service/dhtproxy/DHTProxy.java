/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.service.dhtproxy;

import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Node;
import io.bosonnetwork.service.BosonService;
import io.bosonnetwork.service.BosonServiceException;
import io.bosonnetwork.service.ServiceContext;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

public class DHTProxy implements BosonService {
	static final String ID = "io.bosonnetwork.dhtproxy";
	private static final String NAME = "DHT Proxy";
	private static final int DEFAULT_PORT = 8088;

	private ServiceContext context;
	private ProxyServer server;
	private Vertx vertx;
	private String deploymentId;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public boolean isRunning() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void init(ServiceContext context) throws BosonServiceException {
		Node node = context.getNode();
		int port = (int)context.getConfiguration().getOrDefault("port", 0);
		if (port == 0)
			port = DEFAULT_PORT;

		server = new ProxyServer(node, port);

		this.context = context;

		VertxOptions options = new VertxOptions();
		options.setPreferNativeTransport(true);

		// options.setBlockedThreadCheckIntervalUnit(TimeUnit.SECONDS);
		// options.setBlockedThreadCheckInterval(300);
		this.vertx = Vertx.vertx(options);
	}

	@Override
	public CompletableFuture<Void> start() {
		CompletableFuture<Void> cf = new CompletableFuture<>();

		vertx.deployVerticle(server).onComplete(ar -> {
			if (ar.succeeded()) {
				deploymentId = ar.result();
				cf.complete(null);
			} else {
				cf.completeExceptionally(new BosonServiceException("Can not start service: " + NAME, ar.cause()));
			}
		});

		return cf;
	}

	@Override
	public CompletableFuture<Void> stop() {
		if (deploymentId == null)
			return CompletableFuture.failedFuture(new BosonServiceException("Service not started: " + NAME));

		CompletableFuture<Void> cf = new CompletableFuture<>();
		vertx.undeploy(deploymentId).onComplete(ar -> {
			if (ar.succeeded()) {
				deploymentId = null;
				cf.complete(null);
			} else {
				cf.completeExceptionally(new BosonServiceException("Can not stop service: " + NAME, ar.cause()));
			}
		});

		return cf;
	}
}
