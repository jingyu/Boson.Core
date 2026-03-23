/*
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

package io.bosonnetwork.identifier;

import java.util.concurrent.CompletableFuture;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Node;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.vertx.VertxFuture;

/**
 * Singleton implementation of {@link Registry} that uses a distributed hash table (DHT) to store and resolve Cards.
 * <p>
 * Provides methods to initialize the registry with Vert.x and Node instances, register Cards in the DHT,
 * and retrieve a Resolver for asynchronous Card resolution.
 */
class DHTRegistry implements Registry {
	/** Vert.x instance for asynchronous operations */
	private Vertx vertx;
	/** Local DHT node used for storing and finding values */
	private Node node;
	/** Resolver instance for resolving Cards via DHT */
	private Resolver resolver;
	/** Singleton instance of the registry */
	private static DHTRegistry instance;
	/** Logger for debug and error messages */
	private static final Logger log = LoggerFactory.getLogger(DHTRegistry.class);

	private DHTRegistry() {
	}

	/**
	 * Retrieves the singleton instance of the DHTRegistry.
	 *
	 * @return the singleton Registry instance
	 */
	protected static Registry getInstance() {
		if (instance == null)
			instance = new DHTRegistry();

		return instance;
	}

	/**
	 * Initializes the registry with the required components.
	 *
	 * @param args variable arguments where:
	 *             args[0] must be a Vertx instance for asynchronous operations,
	 *             args[1] must be a Node instance representing the local DHT node,
	 *             args[2] optionally a ResolverCache instance for persistent caching.
	 * @return a CompletableFuture that completes when initialization is done or fails if arguments are invalid or already initialized
	 */
	@Override
	public CompletableFuture<Void> initialize(Object... args) {
		if (vertx != null && node != null)
			return VertxFuture.failedFuture(new IllegalStateException("Already initialized"));

		if (args.length < 2 || args.length > 3)
			return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments length"));

		if (args[0] instanceof Vertx v)
			this.vertx = v;
		else
			return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments: args[0] is not a Vertx instance"));

		if (args[1] instanceof Node n)
			this.node = n;
		else
			return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments: args[1] is not a Node instance"));

		// Optional persistent cache for resolved Cards
		ResolverCache persistentCache;
		if (args.length == 3) {
			if (args[2] instanceof ResolverCache c)
				persistentCache = c;
			else
				return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments: args[2] is not a ResolverCache instance"));
		} else {
			persistentCache = null;
		}

		this.resolver = new CachedResolver(new DHTResolver(node), vertx, persistentCache);

		return VertxFuture.succeededFuture();
	}

	/**
	 * Registers a Card in the DHT by creating a Value object with the given parameters and storing it.
	 *
	 * @param card the Card to register
	 * @param nonce the nonce used in the signature
	 * @param version the version number of the Card
	 * @param signature the cryptographic signature validating the Card
	 * @return a CompletableFuture that completes when the registration is successful or fails if the signature is invalid
	 */
	@Override
	public CompletableFuture<Void> register(Card card, CryptoBox.Nonce nonce, int version, byte[] signature) {
		// Create a Value object encapsulating the Card data and signature details
		Value value = Value.of(card.getId(), nonce.bytes(), version, signature, card.toBytes());
		// Verify the signature validity before proceeding
		if (!value.isValid())
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid signature"));

		log.debug("Registering card {} ...", card.getId());
		// Store the value in the DHT node, forcing an update
		return node.storeValue(value, true);
	}

	/**
	 * Retrieves the Resolver instance used for resolving Cards asynchronously.
	 *
	 * @return the Resolver instance
	 */
	@Override
	public Resolver getResolver() {
		return resolver;
	}
}