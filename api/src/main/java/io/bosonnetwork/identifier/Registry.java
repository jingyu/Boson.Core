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

import io.bosonnetwork.Identity;
import io.bosonnetwork.Node;

/**
 * The {@code Registry} interface defines the contract for registering and resolving Boson {@link Card}s.
 * <p>
 * Implementations of this interface are responsible for securely registering {@code Card} identities
 * and providing mechanisms for their asynchronous resolution. All registration and initialization
 * operations are performed asynchronously using {@link CompletableFuture}, enabling non-blocking workflows.
 * <p>
 * This interface also exposes a default Distributed Hash Table (DHT) based registry implementation
 * via the static DHTRegistry(...) method.
 */
public interface Registry {
	/**
	 * Registers the {@code Card} as Value in the Distributed Hash Table (DHT).
	 * <p>
	 * The registration is performed asynchronously and requires a cryptographic
	 * signature to ensure the authenticity and integrity of the {@code Card} data.
	 * The {@code nonce} and {@code version} parameters provide replay protection and versioning.
	 * </p>
	 *
	 * @param identity the {@code Identity} to register, representing the entity owning the {@code Card}
	 * @param card the {@code Card} containing cryptographic data and metadata to be stored
	 * @param version the version number associated with the {@code Card}; must be a positive integer
	 * @return a {@code CompletableFuture<Void>} indicating the completion of the registration process,
	 *         which resolves successfully when the {@code Card} is stored or exceptionally if an error occurs
	 */
	CompletableFuture<Void> register(Identity identity, Card card, int version);

	/**
	 * Returns a {@link Resolver} for resolving registered {@link Card}s.
	 * <p>
	 * The resolver provides asynchronous lookup capabilities for retrieving cards by their identifier.
	 * </p>
	 *
	 * @return a {@link Resolver} instance associated with this registry
	 */
	Resolver getResolver();

	/**
	 * Creates a new instance of a Distributed Hash Table (DHT)-based {@code Registry}.
	 *
	 * @param node the {@code Node} instance representing the local DHT node; must not be null
	 * @param vertx the {@code Vertx} instance to be used for asynchronous operations; may be null.
	 * @param persistentCache the {@code ResolverCache} implementation to be used for caching resolved entries; may be null
	 * @return a new instance of a DHT-based {@code Registry}
	 */
	static Registry DHTRegistry(Node node, Vertx vertx, ResolverCache persistentCache) {
		Objects.requireNonNull(node, "node");
		return new DHTRegistry(node, vertx, persistentCache);
	}

	/**
	 * Creates a new instance of a Distributed Hash Table (DHT)-based {@code Registry}.
	 *
	 * @param node the {@code Node} instance representing the local DHT node; must not be null
	 * @param persistentCache the {@code ResolverCache} implementation to be used for caching resolved entries; may be null
	 * @return a new instance of a DHT-based {@code Registry}
	 */
	static Registry DHTRegistry(Node node, ResolverCache persistentCache) {
		return new DHTRegistry(node, null, persistentCache);
	}

	/**
	 * Creates a new instance of a Distributed Hash Table (DHT)-based {@code Registry}.
	 *
	 * @param node the {@code Node} instance representing the local DHT node; must not be null
	 * @return a new instance of a DHT-based {@code Registry}
	 */
	static Registry DHTRegistry(Node node) {
		return new DHTRegistry(node, null, null);
	}
}