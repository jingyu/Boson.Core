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
 *       (~2 hours for the built-in DHT registry) and must be re-announced periodically to remain
 *       resolvable; the publishing node does that for the cards it keeps. To "remove" an entry, stop
 *       publishing it ({@link #unpublish(Id)}) and let the lease expire.</li>
 *   <li><strong>Append-only backends (e.g. blockchain).</strong> Each publication is a new transaction;
 *       entries do not auto-expire. Updates supersede prior versions ordered by the version sequence
 *       number. Removal, if supported at all, is backend-specific (e.g. publishing a tombstone
 *       record).</li>
 * </ul>
 * <p>
 * Publishing a card ({@link #publish(Identity, Card, int)}) chooses its version, which must keep
 * increasing: the owner may publish from several devices, a lookup may still remember an expired
 * version, and a backend keeps only a newer version than it has. The publisher's own state
 * ({@link #getPublished(Id)}) and stopping ({@link #unpublish(Id)}) complete the lifecycle of a card an
 * app publishes for its user.
 * <p>
 * Resolving ({@link #resolve(Id)}) asks what the network has for an id; an implementation may cache the
 * answers. What the network has is not what this registry publishes: copies of an unpublished card stay
 * resolvable until they expire, and a card another device published resolves though this registry keeps
 * none.
 * <p>
 * What this interface intentionally <em>does not</em> include:
 * <ul>
 *   <li><strong>No network-wide {@code remove}.</strong> {@link #unpublish(Id)} only stops this
 *       registry's publisher; copies elsewhere go as the backend lets them (TTL expiry, tombstone,
 *       etc.).</li>
 *   <li><strong>No {@code exists} probe.</strong> Existence is whatever {@link #resolve(Id)} reports
 *       right now; resolve and inspect the {@link ResolutionStatus status}. {@link #getPublished(Id)}
 *       reports what this registry's publisher keeps, not what others can resolve.</li>
 * </ul>
 * <p>
 * All operations are asynchronous via {@link CompletableFuture}. The built-in DHT-based
 * implementation is reachable through the {@link #DHTRegistry(Node) DHTRegistry(...)} factories.
 */
public interface Registry {
	/**
	 * Status codes for the result of a resolution attempt.
	 * <ul>
	 *     <li>{@link #SUCCESS}: The resolution succeeded and a valid result was returned.</li>
	 *     <li>{@link #INVALID}: The input was invalid or the resolved data was malformed.</li>
	 *     <li>{@link #NOT_FOUND}: No result could be found for the requested ID.</li>
	 *     <li>{@link #REPRESENTATION_NOT_SUPPORTED}: The requested representation is not supported.</li>
	 *     <li>{@link #UNSUPPORTED_METHOD}: The resolution method is not supported.</li>
	 * </ul>
	 */
	enum ResolutionStatus {
		/**
		 * Resolution succeeded.
		 */
		SUCCESS(0),
		/**
		 * The resolved data was invalid.
		 */
		INVALID(-1),
		/**
		 * No result was found for the requested ID.
		 */
		NOT_FOUND(-2),
		/**
		 * The requested representation is not supported.
		 */
		REPRESENTATION_NOT_SUPPORTED(-3),
		/**
		 * The resolution method is not supported.
		 */
		UNSUPPORTED_METHOD(-4);

		private final int code;

		ResolutionStatus(int code) {
			this.code = code;
		}

		/**
		 * Returns the integer code for this status.
		 * @return integer code corresponding to the status
		 */
		public int getCode() {
			return code;
		}

		/**
		 * Returns the {@link ResolutionStatus} corresponding to the given code.
		 * @param code the integer code
		 * @return the matching {@link ResolutionStatus}
		 * @throws IllegalArgumentException if the code does not correspond to a valid status
		 */
		public static ResolutionStatus from(int code) {
			return switch (code) {
				case 0 -> SUCCESS;
				case -1 -> INVALID;
				case -2 -> NOT_FOUND;
				case -3 -> REPRESENTATION_NOT_SUPPORTED;
				case -4 -> UNSUPPORTED_METHOD;
				default -> throw new IllegalArgumentException("Invalid resolution status: " + code);
			};
		}
	}

	/**
	 * Metadata describing the resolved result, such as creation date, update date, resolution time,
	 * deactivation status, and version.
	 */
	class ResolutionMetadata {
		private final Date created;
		private final Date updated;
		private final Date resolved;
		private final boolean deactivated;
		private final int version;

		/**
		 * @param created date the resource was created
		 * @param updated date the resource was last updated
		 * @param resolved date/time when this result was resolved
		 * @param deactivated whether the resource is deactivated
		 * @param version version number of the resource
		 */
		public ResolutionMetadata(Date created, Date updated, Date resolved, boolean deactivated, int version) {
			this.created = created;
			this.updated = updated;
			this.resolved = resolved;
			this.deactivated = deactivated;
			this.version = version;
		}

		/**
		 * @return the creation date of the resource
		 */
		public Date getCreated() {
			return created;
		}

		/**
		 * @return the last update date of the resource
		 */
		public Date getUpdated() {
			return updated;
		}

		/**
		 * @return the date/time when this result was resolved; a cached result keeps the time it was
		 *         resolved at
		 */
		public Date getResolved() {
			return resolved;
		}

		/**
		 * @return true if the resource is deactivated, false otherwise
		 */
		public boolean isDeactivated() {
			return deactivated;
		}

		/**
		 * @return the version number of the resource
		 */
		public int getVersion() {
			return version;
		}
	}

	/**
	 * Result of a resolution attempt, including status, the resolved object (if any), and associated metadata.
	 *
	 * @param <T> the type of the resolved object (e.g. {@link Card} or {@link DIDDocument})
	 */
	class ResolutionResult<T> {
		@SuppressWarnings("rawtypes")
		private static final ResolutionResult NOT_FOUND = new ResolutionResult<>(ResolutionStatus.NOT_FOUND, null, null);
		@SuppressWarnings("rawtypes")
		private static final ResolutionResult INVALID = new ResolutionResult<>(ResolutionStatus.INVALID, null, null);

		private final ResolutionStatus status;
		private final @Nullable T result;
		private final @Nullable ResolutionMetadata metadata;

		/**
		 * Constructs a resolution result with the given status, result, and metadata.
		 * @param status the resolution status
		 * @param result the resolved object, or null if not found or invalid
		 * @param metadata metadata about the resolved object
		 */
		public ResolutionResult(ResolutionStatus status, @Nullable T result, @Nullable ResolutionMetadata metadata) {
			this.status = status;
			this.result = result;
			this.metadata = metadata;
		}

		/**
		 * Constructs a successful resolution result.
		 * @param result the resolved object
		 * @param metadata metadata about the resolved object
		 */
		public ResolutionResult(T result, @Nullable ResolutionMetadata metadata) {
			this(ResolutionStatus.SUCCESS, result, metadata);
		}

		/**
		 * @return the status of the resolution (e.g. success, not found, invalid)
		 */
		public ResolutionStatus getResolutionStatus() {
			return status;
		}

		/**
		 * @return the resolved object, or null if not found or invalid
		 */
		public @Nullable T getResult() {
			return result;
		}

		/**
		 * Returns the resolved object as an {@link Optional}, empty when the resolution did not
		 * succeed (i.e. {@code NOT_FOUND} or {@code INVALID}). Prefer this over {@link #getResult()}
		 * when you want to avoid an explicit {@code null} check.
		 *
		 * @return the resolved object, or {@link Optional#empty()} if not found or invalid
		 */
		public Optional<T> result() {
			return Optional.ofNullable(result);
		}

		/**
		 * @return metadata about the resolved object
		 */
		public @Nullable ResolutionMetadata getResultMetadata() {
			return metadata;
		}

		/**
		 * @return true if the resolution was successful, false otherwise
		 */
		public boolean succeeded() {
			return status == ResolutionStatus.SUCCESS;
		}

		/**
		 * @return true if the resolution failed (not successful), false otherwise
		 */
		public boolean failed() {
			return status != ResolutionStatus.SUCCESS;
		}

		/**
		 * Returns a static resolution result representing "not found".
		 * @param <T> type parameter
		 * @return a not found result
		 */
		@SuppressWarnings("unchecked")
		public static <T> ResolutionResult<T> notFound() {
			return (ResolutionResult<T>) NOT_FOUND;
		}

		/**
		 * Returns a static resolution result representing "invalid".
		 * @param <T> type parameter
		 * @return an invalid result
		 */
		@SuppressWarnings("unchecked")
		public static <T> ResolutionResult<T> invalid() {
			return (ResolutionResult<T>) INVALID;
		}
	}

	/**
	 * A card a registry's publisher keeps: the version it publishes, and the card itself.
	 */
	final class PublishedCard {
		private final int version;
		private final @Nullable Card card;

		/**
		 * Creates a published card.
		 *
		 * @param version the version published
		 * @param card    the card, or null when what is published doesn't parse as one
		 */
		public PublishedCard(int version, @Nullable Card card) {
			this.version = version;
			this.card = card;
		}

		/**
		 * The version published.
		 *
		 * @return the version
		 */
		public int getVersion() {
			return version;
		}

		/**
		 * The card published, or empty when what is published doesn't parse as one.
		 *
		 * @return the card
		 */
		public Optional<Card> getCard() {
			return Optional.ofNullable(card);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof PublishedCard that && version == that.version && Objects.equals(card, that.card);
		}

		@Override
		public int hashCode() {
			return Objects.hash(version, card);
		}

		@Override
		public String toString() {
			return "PublishedCard(" + version + ", " + card + ")";
		}
	}

	/**
	 * Publishes a signed {@link Card}, choosing its version: one past the highest of the version this
	 * registry's publisher keeps, the newest one the backend has, and {@code lastVersion}, the version
	 * the caller itself last published (another device of the same owner may have published since, and
	 * a card that expired may still be remembered by resolvers, so each publication must be newer than
	 * all of them). The first publication is version 0.
	 * <p>
	 * The persistence model is backend-specific (see the {@linkplain Registry class Javadoc}): on a
	 * <em>lease/TTL backend</em> (DHT) the publisher keeps the card and re-announces it until
	 * {@link #unpublish(Id)}; on an <em>append-only backend</em> (blockchain) the call appends a
	 * transaction.
	 * <p>
	 * Implementations reject the call when the card is not genuine ({@link Card#isGenuine()}) or when
	 * {@code identity.getId() != card.getId()}; both indicate caller misuse.
	 *
	 * @param identity    the identity that owns the card (must match {@code card.getId()})
	 * @param card        the signed {@code Card} to publish
	 * @param lastVersion the version the caller last published, or -1 when it doesn't know of one
	 * @return a future that completes with the version published, or exceptionally if the backend fails
	 */
	CompletableFuture<Integer> publish(Identity identity, Card card, int lastVersion);

	/**
	 * Publishes a signed {@link Card}, choosing its version: see {@link #publish(Identity, Card, int)}.
	 *
	 * @param identity the identity that owns the card (must match {@code card.getId()})
	 * @param card     the signed {@code Card} to publish
	 * @return a future that completes with the version published, or exceptionally if the backend fails
	 */
	default CompletableFuture<Integer> publish(Identity identity, Card card) {
		return publish(identity, card, -1);
	}

	/**
	 * The card this registry's publisher keeps for {@code id}: what it publishes, and at which version.
	 * This is the publisher's own state, not a lookup of what others can resolve ({@link #resolve(Id)}).
	 *
	 * @param id the card's id
	 * @return a future that completes with the published card, or empty when this registry's publisher
	 *         keeps none
	 */
	CompletableFuture<Optional<PublishedCard>> getPublished(Id id);

	/**
	 * Stops publishing the card of {@code id}: this registry's publisher no longer keeps or renews it.
	 * Copies the backend holds elsewhere go as it lets them (on a DHT they expire).
	 *
	 * @param id the card's id
	 * @return a future that completes with true if a card was published, false if none was
	 */
	CompletableFuture<Boolean> unpublish(Id id);

	/**
	 * Resolves a Boson {@link Id} to the {@link Card} the network has for it.
	 *
	 * @param id the Boson ID to resolve
	 * @param useCache whether a cached result may answer; false resolves afresh (and refreshes the cache
	 *                 with the answer)
	 * @return a future containing the resolution result (status, card, and metadata)
	 */
	CompletableFuture<ResolutionResult<Card>> resolve(Id id, boolean useCache);

	/**
	 * Resolves a Boson {@link Id} to the {@link Card} the network has for it; a cached result may answer.
	 *
	 * @param id the Boson ID to resolve
	 * @return a future containing the resolution result (status, card, and metadata)
	 */
	default CompletableFuture<ResolutionResult<Card>> resolve(Id id) {
		return resolve(id, true);
	}

	/**
	 * Resolves a Boson {@link Id} to a {@link DIDDocument}: the {@link Card} is resolved, and if
	 * successful, mapped to a DIDDocument.
	 *
	 * @param id the Boson ID to resolve
	 * @param useCache whether a cached result may answer; false resolves afresh
	 * @return a future containing the resolution result (status, DID document, and metadata)
	 */
	default CompletableFuture<ResolutionResult<DIDDocument>> resolveDocument(Id id, boolean useCache) {
		Objects.requireNonNull(id, "id");

		return resolve(id, useCache).thenApply(rr -> {
			Card card = rr.getResult();
			DIDDocument doc = card == null ? null : DIDDocument.fromCard(card);
			return new ResolutionResult<>(rr.getResolutionStatus(), doc, rr.getResultMetadata());
		});
	}

	/**
	 * Resolves a Boson {@link Id} to a {@link DIDDocument}; a cached result may answer. See
	 * {@link #resolveDocument(Id, boolean)}.
	 *
	 * @param id the Boson ID to resolve
	 * @return a future containing the resolution result (status, DID document, and metadata)
	 */
	default CompletableFuture<ResolutionResult<DIDDocument>> resolveDocument(Id id) {
		return resolveDocument(id, true);
	}

	/**
	 * Creates a DHT-based {@code Registry} whose resolutions are cached in memory, keeping up to 64 cards
	 * for 5 minutes each.
	 *
	 * @param node the node to publish and resolve through, such as the local DHT node or a web gateway
	 *             client; must not be null
	 * @return a new instance of a DHT-based {@code Registry}
	 */
	static Registry DHTRegistry(Node node) {
		return DHTRegistry(node, DHTRegistry.DEFAULT_CACHE_SIZE, DHTRegistry.DEFAULT_CACHE_TTL);
	}

	/**
	 * Creates a DHT-based {@code Registry} whose resolutions are cached in memory, keeping up to
	 * {@code cacheSize} cards for 5 minutes each.
	 *
	 * @param node the node to publish and resolve through, such as the local DHT node or a web gateway
	 *             client; must not be null
	 * @param cacheSize the most cards the cache keeps; 0 keeps no cache, every resolution asks the DHT
	 * @return a new instance of a DHT-based {@code Registry}
	 * @throws IllegalArgumentException if {@code cacheSize} is negative
	 */
	static Registry DHTRegistry(Node node, int cacheSize) {
		return DHTRegistry(node, cacheSize, DHTRegistry.DEFAULT_CACHE_TTL);
	}

	/**
	 * Creates a DHT-based {@code Registry} whose resolutions are cached in memory, keeping up to
	 * {@code cacheSize} cards (the least recently used one goes first). A cached card answers for
	 * {@code ttl} after it was resolved, then the DHT is asked again; an answer of none (not found,
	 * invalid) answers for at most 30 seconds, as a card may be published any moment.
	 *
	 * @param node the node to publish and resolve through, such as the local DHT node or a web gateway
	 *             client; must not be null
	 * @param cacheSize the most cards the cache keeps; 0 keeps no cache, every resolution asks the DHT
	 * @param ttl how long a cached card answers; must be positive
	 * @return a new instance of a DHT-based {@code Registry}
	 * @throws IllegalArgumentException if {@code cacheSize} is negative or {@code ttl} is not positive
	 */
	static Registry DHTRegistry(Node node, int cacheSize, Duration ttl) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(ttl, "ttl");
		return new DHTRegistry(node, cacheSize, ttl);
	}
}
