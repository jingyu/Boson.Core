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

import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Node;
import io.bosonnetwork.Value;
import io.bosonnetwork.vertx.ContextualFuture;

/**
 * Implementation of the {@link Registry} interface that uses a Distributed Hash Table (DHT)
 * for publishing and resolving {@link Card}s.
 * <p>
 * A card is stored as a signed {@link Value} of its owner, at the card's version as the value's sequence
 * number. Resolutions are cached in memory ({@link CardCache}) unless the registry was made without a
 * cache; the cache forgets a card this registry publishes or unpublishes.
 */
class DHTRegistry implements Registry {
	/** How many cards the cache keeps by default */
	static final int DEFAULT_CACHE_SIZE = 64;
	/** How long a cached card answers by default */
	static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

	/** Local DHT node used for storing and finding values */
	private final Node node;
	/** The resolutions cache, null when the registry keeps none */
	private final @Nullable CardCache cache;
	/** Logger for debug and error messages */
	private static final Logger log = LoggerFactory.getLogger(DHTRegistry.class);

	/**
	 * Constructs a new {@code DHTRegistry} backed by the given node.
	 *
	 * @param node the node used for publishing and looking up card values; must not be null
	 * @param cacheSize the most cards the resolution cache keeps; 0 keeps no cache
	 * @param ttl how long a cached card answers
	 * @throws NullPointerException if {@code node} is null
	 * @throws IllegalArgumentException if {@code cacheSize} is negative or {@code ttl} is not positive
	 */
	DHTRegistry(Node node, int cacheSize, Duration ttl) {
		if (cacheSize < 0)
			throw new IllegalArgumentException("Cache size must not be negative");
		if (ttl.isNegative() || ttl.isZero())
			throw new IllegalArgumentException("Cache TTL must be positive");

		this.node = Objects.requireNonNull(node);
		this.cache = cacheSize == 0 ? null : new CardCache(cacheSize, ttl.toMillis());
	}

	/**
	 * Stores the card in the DHT as a value at {@code version}, kept by the node (which announces it again
	 * and again) and forcing an update.
	 */
	private CompletableFuture<Void> register(Identity identity, Card card, int version) {
		// Create a Value object encapsulating the Card data and signature details
		Value value = Value.signedBuilder()
				.identity(identity)
				.sequenceNumber(version)
				.data(card.toBytes())
				.build();

		log.debug("Registering card {} at version {} ...", card.getId(), version);
		// The per-node detail of the store is dropped rather than widening Registry's contract: which
		// DHT nodes took a card is not something a registry caller can act on, and a store that reached
		// nobody still fails the future, so the outcome that matters still arrives.
		return node.storeValue(value, true).thenAccept(result -> { });
	}

	private static void checkCard(Identity identity, Card card) {
		if (!card.isGenuine())
			throw new IllegalArgumentException("Card is not genuine");
		if (!identity.getId().equals(card.getId()))
			throw new IllegalArgumentException("Identity id does not match card id");
	}

	/**
	 * Publishes the card at one past the highest version known: the one this node keeps (its own
	 * publication, kept persistently), the newest the DHT has (another publisher of the same owner may
	 * have published since), and the caller's own last one. A DHT that can't be asked is not an error:
	 * the version is then chosen from what is known.
	 */
	@Override
	public CompletableFuture<Integer> publish(Identity identity, Card card, int lastVersion) {
		Objects.requireNonNull(identity);
		Objects.requireNonNull(card);
		checkCard(identity, card);

		Id id = card.getId();
		return node.getValue(id).thenCompose(kept ->
				node.findValue(id).exceptionally(e -> Optional.empty()).thenCompose(found -> {
					int highest = Math.max(lastVersion, Math.max(
							kept.map(Value::getSequenceNumber).orElse(-1),
							found.map(Value::getSequenceNumber).orElse(-1)));
					int version = highest + 1;
					return register(identity, card, version).thenApply(v -> version);
				})).whenComplete((v, e) -> invalidate(id));
	}

	@Override
	public CompletableFuture<Optional<PublishedCard>> getPublished(Id id) {
		Objects.requireNonNull(id);
		return node.getValue(id).thenApply(kept -> kept.map(value ->
				new PublishedCard(value.getSequenceNumber(), parse(value.getData()))));
	}

	@Override
	public CompletableFuture<Boolean> unpublish(Id id) {
		Objects.requireNonNull(id);
		log.debug("Unpublishing card {} ...", id);
		return node.removeValue(id).whenComplete((v, e) -> invalidate(id));
	}

	// What a published value holds, as a card; null when it doesn't parse as one.
	private static @Nullable Card parse(byte[] data) {
		try {
			return Card.parse(data);
		} catch (RuntimeException e) {
			log.warn("Published value is not a card: {}", e.getMessage());
			return null;
		}
	}

	private void invalidate(Id id) {
		if (cache != null)
			cache.invalidate(id);
	}

	/**
	 * Resolves the card from the cache, or by looking it up in the DHT. A forced resolution asks the DHT
	 * afresh and leaves its answer in the cache.
	 */
	@Override
	public CompletableFuture<ResolutionResult<Card>> resolve(Id id, boolean force) {
		Objects.requireNonNull(id, "id");

		if (cache == null)
			return lookup(id, force);

		CompletableFuture<ResolutionResult<Card>> resolution = force ?
				cache.refresh(id, key -> lookup(key, true)) :
				cache.get(id, key -> lookup(key, false));
		// Answered on the caller's context: a caller that finds an entry another context is loading would
		// otherwise carry on on the loader's thread (a registry is shared by a service's verticles). It also
		// keeps callers from completing the future the cache shares.
		return ContextualFuture.ofCaller(Future.fromCompletionStage(resolution));
	}

	/**
	 * Looks the card up in the DHT, verifying it: the value must be the id's, and hold a genuine card of
	 * the id. An ordinary lookup takes the first value found; a forced one waits for the newest.
	 */
	private CompletableFuture<ResolutionResult<Card>> lookup(Id id, boolean force) {
		LookupOption lookupOption = force ? LookupOption.OPTIMISTIC : LookupOption.ARBITRARY;

		return node.findValue(id, lookupOption).thenApply(v -> {
			// If no value found in DHT, return the not found result
			if (v.isEmpty())
				return ResolutionResult.notFound();

			Value value = v.get();
			// Check that the id matches the public key in the retrieved value
			if (!Objects.equals(id, value.getPublicKey()))
				return ResolutionResult.invalid();

			Card card;
			try {
				// Attempt to parse the Card from the value's data bytes
				card = Card.parse(value.getData());
			} catch (Exception e) {
				// Parsing failed, return the invalid result
				return ResolutionResult.invalid();
			}

			// The resolved card must be for the requested id (defense-in-depth: isGenuine() already
			// binds the card to its own subject, and the value's public key was checked above).
			if (!id.equals(card.getId()))
				return ResolutionResult.invalid();

			// Verify the Card's signature and integrity
			if (!card.isGenuine())
				return ResolutionResult.invalid();

			// Return a successful resolution result with metadata including signature timestamps and the
			// version (the value's sequence number)
			return new ResolutionResult<>(card, new ResolutionMetadata(
					card.getSignedAt(), card.getSignedAt(), new Date(), false, value.getSequenceNumber()));
		});
	}
}
