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

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.Node;

/**
 * Publishes and resolves Boson {@link Card}s.
 * <p>
 * <strong>Persistence semantics are backend-specific.</strong> The two typical models:
 * <ul>
 *   <li><strong>Lease/TTL backends (e.g. DHT).</strong> A published entry is held for a fixed TTL
 *       (~2 hours for the built-in DHT registry) and must be re-published periodically to remain
 *       resolvable. To "remove" an entry, stop re-publishing it and let the lease expire.</li>
 *   <li><strong>Append-only backends (e.g. blockchain).</strong> Each call is a new transaction;
 *       entries do not auto-expire. Updates supersede prior versions ordered by the
 *       {@code version} sequence number. Removal, if supported at all, is backend-specific
 *       (e.g. publishing a tombstone record).</li>
 * </ul>
 * <p>
 * What this interface intentionally <em>does not</em> include:
 * <ul>
 *   <li><strong>No {@code unregister}/{@code remove}.</strong> Removal is a backend concern (TTL
 *       expiry, tombstone, etc.), not a publication-API concern.</li>
 *   <li><strong>No {@code exists} probe.</strong> Existence is whatever
 *       {@link Resolver#resolve(Id)} reports right now; resolve and inspect the
 *       {@link Resolver.ResolutionStatus status}.</li>
 *   <li><strong>No registration receipt.</strong> {@code register} returns
 *       {@code CompletableFuture<Void>}; successful completion is the receipt, and the caller
 *       already knows the {@code version} it submitted.</li>
 * </ul>
 * <p>
 * All operations are asynchronous via {@link CompletableFuture}. The built-in DHT-based
 * implementation is reachable through the {@link #DHTRegistry(Node) DHTRegistry(...)} factories.
 */
public interface Registry {
	/**
	 * Publishes a signed {@link Card} so it can be resolved by its {@link Id}. The persistence
	 * model is backend-specific (see the {@linkplain Registry class Javadoc}):
	 * <ul>
	 *   <li>On a <em>lease/TTL backend</em> (DHT) the published entry expires after the transport's
	 *       TTL unless the owner calls {@code register} again periodically; reusing the same
	 *       {@code version} refreshes the lease, a larger {@code version} publishes updated contents.</li>
	 *   <li>On an <em>append-only backend</em> (blockchain) the call appends a transaction; entries
	 *       do not expire and {@code version} (a sequence number) orders updates per the backend's rules.</li>
	 * </ul>
	 * Implementations should reject the call when the card is not genuine
	 * ({@link Card#isGenuine()}) or when {@code identity.getId() != card.getId()}; both indicate
	 * caller misuse.
	 * <p>
	 * No receipt is returned: successful completion of the future is the receipt, and the caller
	 * already knows the {@code version} it submitted.
	 *
	 * @param identity the identity that owns the card (must match {@code card.getId()})
	 * @param card the signed {@code Card} to publish
	 * @param version the publication sequence number ({@code >= 0}; backend-specific ordering rules apply)
	 * @return a future that completes successfully when the card is published, or completes
	 *         exceptionally if the backend fails
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
	 * @param persistentCache the {@code ResolutionCache} implementation to be used for caching resolved entries; may be null
	 * @return a new instance of a DHT-based {@code Registry}
	 */
	static Registry DHTRegistry(Node node, @Nullable Vertx vertx, @Nullable ResolutionCache persistentCache) {
		Objects.requireNonNull(node, "node");
		return new DHTRegistry(node, vertx, persistentCache);
	}

	/**
	 * Creates a new instance of a Distributed Hash Table (DHT)-based {@code Registry}.
	 *
	 * @param node the {@code Node} instance representing the local DHT node; must not be null
	 * @param persistentCache the {@code ResolutionCache} implementation to be used for caching resolved entries; may be null
	 * @return a new instance of a DHT-based {@code Registry}
	 */
	static Registry DHTRegistry(Node node, @Nullable ResolutionCache persistentCache) {
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