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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Identity;
import io.bosonnetwork.Node;
import io.bosonnetwork.Value;

/**
 * Implementation of the {@link Registry} interface that uses a Distributed Hash Table (DHT)
 * for securely registering and resolving {@link Card}s.
 * <p>
 * This class provides mechanisms for storing cryptographic identities and metadata within a DHT
 * for decentralized access and lookup. It also integrates a caching layer to optimize the
 * resolution process, leveraging asynchronous operations via Vert.x.
 */
class DHTRegistry implements Registry {
	/** Local DHT node used for storing and finding values */
	private final Node node;
	/** Resolver instance for resolving Cards via DHT */
	private final Resolver resolver;
	/** Logger for debug and error messages */
	private static final Logger log = LoggerFactory.getLogger(DHTRegistry.class);

	/**
	 * Constructs a new instance of {@code DHTRegistry}, initializing it with the provided Vert.x instance,
	 * local DHT node, and a persistent cache for resolving values.
	 *
	 * @param node the local DHT node used for storing and retrieving values; must not be null
	 * @param vertx the Vert.x instance used for asynchronous operations; must be null
	 * @param persistentCache the cache instance used to persist resolver data; can be null depending on caching needs
	 * @throws NullPointerException if {@code vertx} or {@code node} is null
	 */
	protected DHTRegistry(Node node, Vertx vertx, ResolverCache persistentCache) {
		Objects.requireNonNull(node);

		this.node = node;
		this.resolver = new CachedResolver(new DHTResolver(node), vertx, persistentCache);
	}

	/**
	 * Registers the {@code Card} as Value in the Distributed Hash Table (DHT).
	 *
	 * @param identity the {@code Identity} to register, representing the entity owning the {@code Card}
	 * @param card the {@code Card} containing cryptographic data and metadata to be stored
	 * @param version the version number associated with the {@code Card}; must be a positive integer
	 * @return a {@code CompletableFuture<Void>} indicating the completion of the registration process,
	 *         which resolves successfully when the {@code Card} is stored or exceptionally if an error occurs
	 * @throws NullPointerException if the {@code identity} or {@code card} is {@code null}
	 * @throws IllegalArgumentException if the {@code version} is negative, the {@code card} is not genuine,
	 *                                  or the {@code Identity} ID does not match the {@code Card} ID
	 */
	@Override
	public CompletableFuture<Void> register(Identity identity, Card card, int version) {
		Objects.requireNonNull(identity);
		Objects.requireNonNull(card);
		if (version < 0)
			throw new IllegalArgumentException("Version must be a positive integer");
		if (!card.isGenuine())
			throw new IllegalArgumentException("Card is not genuine");
		if (!identity.getId().equals(card.getId()))
			throw new IllegalArgumentException("Identity id does not match card id");

		// Create a Value object encapsulating the Card data and signature details
		Value value = Value.signedBuilder()
				.identity(identity)
				.sequenceNumber(version)
				.data(card.toBytes())
				.build();

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