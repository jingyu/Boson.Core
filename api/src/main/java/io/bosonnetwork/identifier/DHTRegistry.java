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

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Node;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.utils.vertx.VertxFuture;

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
	/** Optional persistent cache for resolved Cards */
	private ResolverCache persistentCache;
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

		if (args.length == 3) {
			if (args[2] instanceof ResolverCache c)
				this.persistentCache = c;
			else
				return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments: args[2] is not a ResolverCache instance"));
		} else {
			this.persistentCache = null;
		}

		this.resolver = new DHTResolver();

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

	/**
	 * Resolver implementation that resolves Cards from the DHT.
	 * <p>
	 * Uses the outer class's Node instance and optional persistent cache to fetch Card data asynchronously.
	 * Performs signature verification and validation before returning results.
	 */
	class DHTResolver extends AbstractResolver {
		public DHTResolver() {
			super(vertx, persistentCache);
		}

		@Override
		protected CompletableFuture<ResolutionResult<Card>> resolveId(Id id) {
			// Ensure the id is not null
			Objects.requireNonNull(id, "id");

			log.debug("Resolving {} ...", id);
			return node.findValue(id).thenApply(value -> {
				// If no value found in DHT, return not found result
				if (value == null)
					return ResolutionResult.notfound();

				// Check that the id matches the public key in the retrieved value
				if (!Objects.equals(id, value.getPublicKey()))
					return ResolutionResult.invalid();

				Card card;
				try {
					// Attempt to parse the Card from the value's data bytes
					card = Card.parse(value.getData());
				} catch (Exception e) {
					// Parsing failed, return invalid result
					return ResolutionResult.invalid();
				}

				// Verify the Card's signature and integrity
				if (!card.isGenuine())
					return ResolutionResult.invalid();

				// Retrieve the version (sequence number) from the value
				int version = value.getSequenceNumber();

				// Return a successful resolution result with metadata including signature timestamps and version
				return new ResolutionResult<>(card, new ResolutionResultMetadata(
						card.getSignedAt(), card.getSignedAt(), new Date(), false, version));
			});
		}

		@Override
		protected Logger log() {
			return log;
		}
	}
}